package com.opticaguinto.optica.report.controller;

import com.opticaguinto.optica.common.service.ClinicSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final JdbcTemplate db;
    private final ClinicSupport clinic;
    private final ZoneId zone;

    public ReportController(JdbcTemplate db, ClinicSupport clinic, @Value("${app.business-zone}") String zone) {
        this.db = db;
        this.clinic = clinic;
        this.zone = ZoneId.of(zone);
    }

    // Los días se interpretan en la zona de la sucursal. El límite superior es
    // exclusivo.
    record Range(OffsetDateTime from, OffsetDateTime to) {
    }

    Range range(LocalDate from, LocalDate to) {
        if (from == null || to == null || !to.isAfter(from))
            throw new ResponseStatusException(BAD_REQUEST, "Se requiere un intervalo from < to");
        return new Range(from.atStartOfDay(zone).toOffsetDateTime(), to.atStartOfDay(zone).toOffsetDateTime());
    }

    String filter(LocalDate from, LocalDate to, List<Object> args) {
        if (from == null && to == null)
            return "";
        var r = range(from, to);
        args.add(r.from());
        args.add(r.to());
        return " AND s.created_at>=? AND s.created_at<?";
    }

    @GetMapping("/reports/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public Object summary(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        var args = new ArrayList<Object>();
        String interval = filter(from, to, args);
        return db.queryForMap("""
                SELECT count(*) AS sales,coalesce(sum(s.total),0) AS total,coalesce(sum(s.paid),0) AS paid,
                coalesce(sum(s.total-s.paid),0) AS pending
                FROM sales s WHERE s.status IN ('OPEN','DELIVERED')
                """ + interval, args.toArray());
    }

    @GetMapping("/reports/sales-by-seller")
    @PreAuthorize("hasRole('ADMIN')")
    public Object bySeller(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var r = range(from, to);
        return db.queryForList("""
                SELECT u.id AS seller_id,u.first_name,u.paternal_surname,count(*) AS sales,sum(s.total) AS total,
                       sum(s.paid) AS paid,sum(s.total-s.paid) AS pending
                FROM sales s JOIN users u ON u.id=s.seller_id
                WHERE s.status IN ('OPEN','DELIVERED') AND s.created_at>=? AND s.created_at<?
                GROUP BY u.id ORDER BY total DESC,u.id LIMIT ? OFFSET ?
                """, r.from(), r.to(), clinic.pageSize(size), clinic.offset(page, size));
    }

    @GetMapping("/reports/top-products")
    @PreAuthorize("hasRole('ADMIN')")
    public Object topProducts(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var r = range(from, to);
        return db.queryForList("""
                SELECT p.id AS product_id,p.sku,p.name,sum(i.quantity) AS quantity,
                       sum(i.quantity*i.unit_price) AS total
                FROM sale_items i JOIN sales s ON s.id=i.sale_id JOIN products p ON p.id=i.product_id
                WHERE s.status IN ('OPEN','DELIVERED') AND s.created_at>=? AND s.created_at<?
                GROUP BY p.id ORDER BY quantity DESC,p.id LIMIT ? OFFSET ?
                """, r.from(), r.to(), clinic.pageSize(size), clinic.offset(page, size));
    }

    // Son saldos actuales de ventas creadas en el intervalo, no una reconstrucción
    // histórica de cartera.
    @GetMapping("/reports/patient-balances")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Object balances(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var args = new ArrayList<Object>();
        String interval = filter(from, to, args);
        args.add(clinic.pageSize(size));
        args.add(clinic.offset(page, size));
        return db.queryForList("""
                SELECT p.id AS patient_id,p.first_name,p.last_name,p.phone,count(*) AS sales,
                       sum(s.total) AS total,sum(s.paid) AS paid,sum(s.total-s.paid) AS pending
                FROM sales s JOIN patients p ON p.id=s.patient_id
                WHERE s.status='OPEN' AND s.total>s.paid
                """ + interval + """
                 GROUP BY p.id ORDER BY pending DESC,p.id LIMIT ? OFFSET ?
                """, args.toArray());
    }

    public record MinimumStockInput(@NotNull @Min(0) Integer minimumStock) {
    }

    @PatchMapping("/products/{id}/minimum-stock")
    @PreAuthorize("hasRole('ADMIN')")
    public Object minimum(@PathVariable long id, @Valid @RequestBody MinimumStockInput input) {
        if (db.update("UPDATE products SET minimum_stock=? WHERE id=?", input.minimumStock(), id) == 0)
            throw new ResponseStatusException(NOT_FOUND, "Producto no encontrado");
        return clinic.one("products", id);
    }

    @GetMapping("/reports/low-stock")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Object lowStock(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return db.queryForList("""
                SELECT id,sku,name,stock,minimum_stock FROM products WHERE active AND stock<=minimum_stock
                ORDER BY stock,id LIMIT ? OFFSET ?
                """, clinic.pageSize(size), clinic.offset(page, size));
    }
}
