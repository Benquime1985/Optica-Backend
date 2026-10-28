package com.opticaguinto.optica.sale.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import com.opticaguinto.optica.common.service.ClinicSupport;
import com.opticaguinto.optica.cash.service.CashService;
import com.opticaguinto.optica.workorder.service.WorkOrderService;

@RestController
@RequestMapping("/api")
public class SaleController {

  private final JdbcTemplate db;
  private final ClinicSupport clinic;
  private final CashService cash;
  private final WorkOrderService orders;
  public SaleController(JdbcTemplate db, ClinicSupport clinic, CashService cash, WorkOrderService orders) { this.db=db; this.clinic=clinic;this.cash=cash; this.orders=orders; }


    // Cada partida identifica un producto y una cantidad positiva; el precio lo
    // determina el servidor.
    public record Line(@Positive long productId, @Min(1) @Max(100000) int quantity) {
    }


    // Una venta pertenece a un paciente y admite hasta 100 partidas de entrada.
    public record SaleInput(@Positive long patientId, @NotEmpty @Size(max = 100) List<@Valid Line> items) {
    }


    // Venta, partidas, descuento de existencias y movimientos forman una única
    // transacción.
    @PostMapping("/sales")
    @ResponseStatus(CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    @Transactional
    public Object sale(@Valid @RequestBody SaleInput s, Authentication a) {
        clinic.activePatient(s.patientId());
        // Agrupa productos repetidos y los ordena por ID para adquirir bloqueos siempre
        // en el mismo orden.
        var quantities = new TreeMap<Long, Integer>();
        for (var item : s.items())
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        long user = clinic.userId(a);
        var prices = new HashMap<Long, BigDecimal>();
        // BigDecimal conserva la precisión decimal durante el cálculo de importes.
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : quantities.entrySet()) {
            // Bloquea cada producto para que otra venta no consuma las mismas existencias
            // simultáneamente.
            var rows = db.queryForList("SELECT * FROM products WHERE id=? FOR UPDATE", entry.getKey());
            if (rows.isEmpty())
                throw new ResponseStatusException(NOT_FOUND, "Producto no encontrado");
            var p = rows.getFirst();
            if (!Boolean.TRUE.equals(p.get("active")) || ((Number) p.get("stock")).intValue() < entry.getValue())
                throw new ResponseStatusException(CONFLICT, "Producto inactivo o existencias insuficientes");
            // El precio procede del catálogo bloqueado, nunca del cuerpo de la petición.
            BigDecimal price = (BigDecimal) p.get("price");
            prices.put(entry.getKey(), price);
            total = total.add(price.multiply(BigDecimal.valueOf(entry.getValue())));
        }
        // Comprueba que el importe quepa en la columna NUMERIC(12,2).
        if (total.compareTo(new BigDecimal("9999999999.99")) > 0)
            throw new ResponseStatusException(BAD_REQUEST, "Total fuera de rango");
        // Tras validar todo el catálogo, registra la venta y conserva el precio
        // unitario de cada partida.
        long id = db.queryForObject("INSERT INTO sales(patient_id,seller_id,total,paid) VALUES (?,?,?,0) RETURNING id",
                Long.class, s.patientId(), user, total);
        for (var entry : quantities.entrySet()) {
            db.update("INSERT INTO sale_items(sale_id,product_id,quantity,unit_price) VALUES (?,?,?,?)", id,
                    entry.getKey(), entry.getValue(), prices.get(entry.getKey()));
            db.update("UPDATE products SET stock=stock-? WHERE id=?", entry.getValue(), entry.getKey());
            movement(entry.getKey(), -entry.getValue(), "Venta " + id, user);
        }
        return saleDetail(id);
    }


    // Lista las ventas en orden de creación descendente.
    @GetMapping("/sales")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Object sales(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return db.queryForList("SELECT * FROM sales ORDER BY id DESC LIMIT ? OFFSET ?", clinic.pageSize(size),
                clinic.offset(page, size));
    }


    // Reúne la cabecera, las partidas y los pagos de una venta.
    @GetMapping("/sales/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Object saleDetail(@PathVariable long id) {
        var result = new LinkedHashMap<>(clinic.one("sales", id));
        result.put("items", db.queryForList("SELECT * FROM sale_items WHERE sale_id=? ORDER BY id", id));
        result.put("payments", db.queryForList("SELECT * FROM payments WHERE sale_id=? ORDER BY id", id));
        result.put("refunds", db.queryForList("SELECT * FROM refunds WHERE sale_id=? ORDER BY id", id));
        result.put("work_orders", db.queryForList("SELECT * FROM work_orders WHERE sale_id=? ORDER BY id", id));
        return result;
    }


    // Se usa dentro de transacciones para serializar pagos y cierres sobre una
    // venta abierta.
    Map<String, Object> lockSale(long id) {
        var rows = db.queryForList("SELECT * FROM sales WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty())
            throw new ResponseStatusException(NOT_FOUND, "Venta no encontrada");
        if (!"OPEN".equals(rows.getFirst().get("status")))
            throw new ResponseStatusException(CONFLICT, "La venta ya está cerrada");
        return rows.getFirst();
    }


    // Solo se aceptan abonos positivos con un máximo de dos decimales.
    public record PaymentInput(@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp="CASH|CARD|TRANSFER") String method, @Size(max=120) String reference) {
        // Compatibilidad con llamadas Java anteriores; HTTP debe enviar el método.
        public PaymentInput(BigDecimal amount) { this(amount,"CASH",null); }
    }


    // El bloqueo evita que dos abonos concurrentes superen juntos el saldo de la
    // venta.
    @PostMapping("/sales/{id}/payments")
    @ResponseStatus(CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    @Transactional
    public Object payment(@PathVariable long id, @Valid @RequestBody PaymentInput p, Authentication a) {
        var sale = lockSale(id);
        cash.validateMethod(p.method(), p.reference());
        var date = cash.lockToday();
        BigDecimal paid = ((BigDecimal) sale.get("paid")).add(p.amount());
        if (paid.compareTo((BigDecimal) sale.get("total")) > 0)
            throw new ResponseStatusException(CONFLICT, "El pago supera el saldo pendiente");
        db.update("INSERT INTO payments(sale_id,amount,received_by,method,reference,business_date) VALUES (?,?,?,?,?,?)",
                id, p.amount(), clinic.userId(a), p.method(), p.reference(), date);
        db.update("UPDATE sales SET paid=? WHERE id=?", paid, id);
        return saleDetail(id);
    }


    // La entrega exige liquidación completa y cierra la venta para nuevas
    // operaciones.
    @PostMapping("/sales/{id}/deliver")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    @Transactional
    public Object deliver(@PathVariable long id, Authentication auth) {
        var s = lockSale(id);
        if (((BigDecimal) s.get("paid")).compareTo((BigDecimal) s.get("total")) != 0)
            throw new ResponseStatusException(CONFLICT, "La venta tiene saldo pendiente");
        if (db.queryForObject("SELECT count(*) FROM work_orders WHERE sale_id=? AND status<>'READY'", Long.class, id)>0)
            throw new ResponseStatusException(CONFLICT,"Todas las órdenes deben estar listas antes de entregar");
        long user=clinic.userId(auth);
        orders.closeAll(id,"DELIVERED",user);
        db.update("UPDATE sales SET status='DELIVERED',closed_at=CURRENT_TIMESTAMP,closed_by=? WHERE id=?", user,id);
        return saleDetail(id);
    }


    // Cancela la venta abierta, reembolsa los abonos y repone existencias en una sola transacción.
    @PostMapping("/sales/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Object cancel(@PathVariable long id, Authentication a) {
        var s = lockSale(id);
        long user=clinic.userId(a);
        cash.refundAll(id,"Cancelación de venta",user);
        restoreStock(id,user,"Cancelación ");
        orders.closeAll(id,"CANCELLED",user);
        db.update("UPDATE sales SET status='CANCELLED',closed_at=CURRENT_TIMESTAMP,closed_by=?,close_reason='Cancelación de venta' WHERE id=?",user,id);
        return saleDetail(id);
    }


    void restoreStock(long id,long user,String reason) {
        // Repone productos en orden de ID para mantener el mismo orden de bloqueo que
        // las ventas.
        for (var item : db.queryForList("SELECT * FROM sale_items WHERE sale_id=? ORDER BY product_id", id)) {
            long product = ((Number) item.get("product_id")).longValue();
            int quantity = ((Number) item.get("quantity")).intValue();
            if(db.update("UPDATE products SET stock=stock+? WHERE id=? AND stock::bigint+?<=2147483647", quantity, product,quantity)==0)
                throw new ResponseStatusException(CONFLICT,"La devolución supera el rango de existencias");
            movement(product, quantity, reason + id, user);
        }
    }


    public record RefundInput(@Positive long paymentId,
            @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount,
            @NotBlank @Pattern(regexp="CASH|CARD|TRANSFER") String method,@Size(max=120) String reference,
            @NotBlank @Size(max=500) String reason) {}


    // El abono original se conserva. Un reembolso parcial en una venta abierta aumenta su saldo pendiente.
    @PostMapping("/sales/{id}/refunds")
    @ResponseStatus(CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Object refund(@PathVariable long id,@Valid @RequestBody RefundInput input,Authentication auth) {
        lockSale(id);
        cash.validateMethod(input.method(),input.reference());
        var date=cash.lockToday();
        cash.refund(id,input.paymentId(),input.amount(),input.method(),input.reference(),input.reason(),clinic.userId(auth),date);
        return saleDetail(id);
    }


    public record ReturnInput(@NotBlank @Size(max=500) String reason) {}

    // La devolución es total: repone todas las partidas y reembolsa el remanente de todos los cobros.
    @PostMapping("/sales/{id}/return")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Object returnSale(@PathVariable long id,@Valid @RequestBody ReturnInput input,Authentication auth) {
        var rows=db.queryForList("SELECT * FROM sales WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Venta no encontrada");
        if(!"DELIVERED".equals(rows.getFirst().get("status")))
            throw new ResponseStatusException(CONFLICT,"Solo se devuelven ventas entregadas");
        long user=clinic.userId(auth);
        cash.refundAll(id,input.reason(),user);
        restoreStock(id,user,"Devolución ");
        orders.closeAll(id,"RETURNED",user);
        db.update("UPDATE sales SET status='RETURNED',closed_at=CURRENT_TIMESTAMP,closed_by=?,close_reason=? WHERE id=?",user,input.reason(),id);
        return saleDetail(id);
    }


    // Registra cantidad, motivo y responsable. La transacción corresponde al método
    // que lo invoca.
    void movement(long product, int quantity, String reason, long user) {
        db.update("INSERT INTO stock_movements(product_id,quantity,reason,user_id) VALUES (?,?,?,?)", product, quantity,
                reason, user);
    }
}
