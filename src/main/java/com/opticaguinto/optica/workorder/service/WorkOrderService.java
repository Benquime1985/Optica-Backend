package com.opticaguinto.optica.workorder.service;

import com.opticaguinto.optica.common.service.ClinicSupport;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class WorkOrderService {
    private final JdbcTemplate db;
    private final ClinicSupport clinic;

    public WorkOrderService(JdbcTemplate db, ClinicSupport clinic) {
        this.db = db;
        this.clinic = clinic;
    }

    public record OrderInput(@Positive long saleId, @Positive long prescriptionId,
            @NotBlank @Size(max = 100) String lensType, @NotBlank @Size(max = 100) String material,
            @DecimalMin("1") @DecimalMax("2") @Digits(integer = 1, fraction = 2) BigDecimal refractiveIndex,
            @Size(max = 200) String coating, @Size(max = 100) String tint,
            @NotBlank @Size(max = 200) String laboratory, @NotNull LocalDate promisedDate,
            @Size(max = 2000) String notes) {
    }

    Map<String, Object> lockSale(long id) {
        var rows = db.queryForList("SELECT * FROM sales WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty())
            throw new ResponseStatusException(NOT_FOUND, "Venta no encontrada");
        if (!"OPEN".equals(rows.getFirst().get("status")))
            throw new ResponseStatusException(CONFLICT, "La venta ya está cerrada");
        return rows.getFirst();
    }

    void validatePrescription(OrderInput input, Map<String, Object> sale) {
        var prescription = clinic.one("prescriptions", input.prescriptionId());
        if (!prescription.get("patient_id").equals(sale.get("patient_id")))
            throw new ResponseStatusException(CONFLICT, "La receta y la venta deben pertenecer al mismo paciente");
        // La fecha prometida puede ser histórica al corregir una orden atrasada.
    }

    @Transactional
    public Object create(OrderInput p, long user) {
        var sale = lockSale(p.saleId());
        validatePrescription(p, sale);
        long id = db.queryForObject(
                """
                        INSERT INTO work_orders(sale_id,prescription_id,lens_type,material,refractive_index,coating,tint,laboratory,promised_date,notes,created_by)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                        """,
                Long.class, p.saleId(), p.prescriptionId(), p.lensType(), p.material(), p.refractiveIndex(),
                p.coating(), p.tint(), p.laboratory(), p.promisedDate(), p.notes(), user);
        event(id, "IN_PROGRESS", user);
        return detail(id);
    }

    @Transactional
    public Object edit(long id, OrderInput p, long user) {
        var existing = clinic.one("work_orders", id);
        long saleId = ((Number) existing.get("sale_id")).longValue();
        var sale = lockSale(saleId);
        existing = clinic.one("work_orders", id);
        if (saleId != p.saleId() || !"IN_PROGRESS".equals(existing.get("status")))
            throw new ResponseStatusException(CONFLICT, "Solo se editan órdenes en elaboración, sin cambiar su venta");
        validatePrescription(p, sale);
        db.update("""
                UPDATE work_orders SET prescription_id=?,lens_type=?,material=?,refractive_index=?,coating=?,tint=?,
                  laboratory=?,promised_date=?,notes=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, p.prescriptionId(), p.lensType(), p.material(), p.refractiveIndex(), p.coating(), p.tint(),
                p.laboratory(), p.promisedDate(), p.notes(), id);
        return detail(id);
    }

    @Transactional
    public Object status(long id, String status, long user) {
        if (!Set.of("IN_PROGRESS", "READY").contains(status))
            throw new ResponseStatusException(BAD_REQUEST, "Estado de orden inválido");
        var order = clinic.one("work_orders", id);
        lockSale(((Number) order.get("sale_id")).longValue());
        order = clinic.one("work_orders", id);
        String current = order.get("status").toString();
        // READY puede volver a elaboración para una corrección. La entrega ocurre desde
        // la venta.
        if (!Set.of("IN_PROGRESS", "READY").contains(current) || current.equals(status))
            throw new ResponseStatusException(CONFLICT, "Transición de orden inválida");
        db.update("UPDATE work_orders SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, id);
        event(id, status, user);
        return detail(id);
    }

    public Object detail(long id) {
        var result = new LinkedHashMap<>(clinic.one("work_orders", id));
        result.put("events", db.queryForList("SELECT * FROM work_order_events WHERE work_order_id=? ORDER BY id", id));
        return result;
    }

    void event(long id, String status, long user) {
        db.update("INSERT INTO work_order_events(work_order_id,status,changed_by) VALUES (?,?,?)", id, status, user);
    }

    // El llamador mantiene bloqueada la venta; así no se crean o cambian órdenes
    // durante el cierre.
    public void closeAll(long sale, String status, long user) {
        for (var order : db.queryForList("SELECT id FROM work_orders WHERE sale_id=? ORDER BY id", sale)) {
            long id = ((Number) order.get("id")).longValue();
            db.update("UPDATE work_orders SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, id);
            event(id, status, user);
        }
    }
}
