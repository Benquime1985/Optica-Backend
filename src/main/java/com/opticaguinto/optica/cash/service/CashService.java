package com.opticaguinto.optica.cash.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

// El bloqueo del día impide que un cobro o reembolso cambie un corte ya cerrado.
@Service
public class CashService {
    private final JdbcTemplate db;
    private final ZoneId zone;
    public CashService(JdbcTemplate db, @Value("${app.business-zone}") String zone) {
        this.db = db;
        this.zone = ZoneId.of(zone);
    }
    public LocalDate today() { return LocalDate.now(zone); }

    // Exige una transacción externa: mantiene el bloqueo hasta confirmar también la venta.
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDate lockToday() {
        LocalDate date = today();
        lockDay(date);
        return date;
    }
    void lockDay(LocalDate date) {
        db.update("INSERT INTO cash_days(business_date) VALUES (?) ON CONFLICT DO NOTHING", date);
        var day = db.queryForMap("SELECT * FROM cash_days WHERE business_date=? FOR UPDATE", date);
        if (day.get("closed_at") != null)
            throw new ResponseStatusException(CONFLICT, "La caja de este día ya está cerrada");
    }
    public void validateMethod(String method, String reference) {
        if (!Set.of("CASH","CARD","TRANSFER").contains(method == null ? "" : method))
            throw new ResponseStatusException(BAD_REQUEST, "Método de pago inválido");
        if (!"CASH".equals(method) && (reference == null || reference.isBlank()))
            throw new ResponseStatusException(BAD_REQUEST, "Tarjeta y transferencia requieren referencia");
    }

    public List<Map<String,Object>> totals(LocalDate date) {
        return db.queryForList("""
            SELECT m.method, coalesce(p.amount,0) AS collected, coalesce(r.amount,0) AS refunded,
                   coalesce(p.amount,0)-coalesce(r.amount,0) AS net
            FROM (VALUES ('CASH'),('CARD'),('TRANSFER'),('UNSPECIFIED')) AS m(method)
            LEFT JOIN (SELECT method,sum(amount) amount FROM payments WHERE business_date=? GROUP BY method) p ON p.method=m.method
            LEFT JOIN (SELECT method,sum(amount) amount FROM refunds WHERE business_date=? GROUP BY method) r ON r.method=m.method
            ORDER BY m.method
            """, date, date);
    }
    public Object day(LocalDate date) {
        var result = new LinkedHashMap<String,Object>();
        result.put("business_date", date);
        var rows = db.queryForList("SELECT * FROM cash_days WHERE business_date=?", date);
        boolean closed = !rows.isEmpty() && rows.getFirst().get("closed_at") != null;
        result.put("closed", closed);
        result.put("close", closed ? rows.getFirst() : null);
        result.put("totals", closed
            ? db.queryForList("SELECT method,collected,refunded,net FROM cash_close_totals WHERE business_date=? ORDER BY method",date)
            : totals(date));
        return result;
    }
    @Transactional
    public Object open(LocalDate date,BigDecimal openingCash,long user) {
        if(!date.equals(today())) throw new ResponseStatusException(BAD_REQUEST,"Solo se puede abrir la caja del día actual");
        lockDay(date);
        var day=db.queryForMap("SELECT * FROM cash_days WHERE business_date=?",date);
        if(day.get("opened_at")!=null || db.queryForObject("""
            SELECT (SELECT count(*) FROM payments WHERE business_date=?)
                 + (SELECT count(*) FROM refunds WHERE business_date=?)
            """,Long.class,date,date)>0)
            throw new ResponseStatusException(CONFLICT,"El fondo se establece una sola vez antes del primer movimiento");
        db.update("UPDATE cash_days SET opening_cash=?,opened_at=CURRENT_TIMESTAMP,opened_by=? WHERE business_date=?",openingCash,user,date);
        return day(date);
    }
    @Transactional
    public Object close(LocalDate date, BigDecimal countedCash, String notes, long user) {
        if (date.isAfter(today())) throw new ResponseStatusException(BAD_REQUEST,"No se puede cerrar una fecha futura");
        lockDay(date);
        var totals = totals(date);
        BigDecimal expected = db.queryForObject("SELECT opening_cash FROM cash_days WHERE business_date=?",BigDecimal.class,date);
        for (var row : totals) {
            db.update("INSERT INTO cash_close_totals(business_date,method,collected,refunded,net) VALUES (?,?,?,?,?)",
                date,row.get("method"),row.get("collected"),row.get("refunded"),row.get("net"));
            if ("CASH".equals(row.get("method"))) expected = expected.add((BigDecimal) row.get("net"));
        }
        db.update("""
            UPDATE cash_days SET closed_at=CURRENT_TIMESTAMP,closed_by=?,counted_cash=?,expected_cash=?,difference=?,notes=?
            WHERE business_date=?
            """,user,countedCash,expected,countedCash.subtract(expected),notes,date);
        return day(date);
    }

    // Se conserva el cobro original. El saldo pagado de la venta representa cobros menos reembolsos.
    @Transactional(propagation = Propagation.MANDATORY)
    public long refund(long sale, long payment, BigDecimal amount, String method, String reference,
                       String reason, long user, LocalDate date) {
        var rows = db.queryForList("""
            SELECT p.*,p.amount-coalesce((SELECT sum(r.amount) FROM refunds r WHERE r.payment_id=p.id),0) AS available
            FROM payments p WHERE p.id=? AND p.sale_id=?
            """,payment,sale);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Cobro no encontrado en esta venta");
        if (amount.compareTo((BigDecimal)rows.getFirst().get("available"))>0)
            throw new ResponseStatusException(CONFLICT,"El reembolso supera el importe disponible del cobro");
        long id = db.queryForObject("""
            INSERT INTO refunds(payment_id,sale_id,amount,method,reference,reason,received_by,business_date)
            VALUES (?,?,?,?,?,?,?,?) RETURNING id
            """,Long.class,payment,sale,amount,method,reference,reason,user,date);
        db.update("UPDATE sales SET paid=paid-? WHERE id=?",amount,sale);
        return id;
    }
    // Al devolver/cancelar una venta, reembolsa cada cobro por su medio original.
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundAll(long sale, String reason, long user) {
        var payments = db.queryForList("""
            SELECT p.*,p.amount-coalesce((SELECT sum(r.amount) FROM refunds r WHERE r.payment_id=p.id),0) AS available
            FROM payments p WHERE p.sale_id=? ORDER BY p.id
            """,sale);
        LocalDate date = null;
        for (var payment : payments) {
            BigDecimal amount = (BigDecimal)payment.get("available");
            if (amount.signum() == 0) continue;
            if (date == null) date = lockToday();
            refund(sale,((Number)payment.get("id")).longValue(),amount,payment.get("method").toString(),
                (String)payment.get("reference"),reason,user,date);
        }
    }
}
