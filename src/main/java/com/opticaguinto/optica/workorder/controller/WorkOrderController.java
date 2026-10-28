package com.opticaguinto.optica.workorder.controller;

import com.opticaguinto.optica.common.service.ClinicSupport;
import com.opticaguinto.optica.workorder.service.WorkOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CREATED;
import org.springframework.http.ResponseEntity;
import com.opticaguinto.optica.common.web.CreatedResource;

@RestController
@RequestMapping("/api/work-orders")
@PreAuthorize("hasAnyRole('ADMIN','OPTOMETRIST','SELLER')")
public class WorkOrderController {
    private final WorkOrderService service;
    private final ClinicSupport clinic;
    private final JdbcTemplate db;

    public WorkOrderController(WorkOrderService service, ClinicSupport clinic, JdbcTemplate db) {
        this.service = service;
        this.clinic = clinic;
        this.db = db;
    }

    @PostMapping
    public ResponseEntity<Object> create(@Valid @RequestBody WorkOrderService.OrderInput input, Authentication auth) {
        Object created = service.create(input, clinic.userId(auth));
        long id = ((Number)((java.util.Map<?, ?>) created).get("id")).longValue();
        return CreatedResource.at("/api/work-orders/{id}", id, created);
    }

    @PutMapping("/{id}")
    public Object edit(@PathVariable long id, @Valid @RequestBody WorkOrderService.OrderInput input,
            Authentication auth) {
        return service.edit(id, input, clinic.userId(auth));
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable long id) {
        return service.detail(id);
    }

    // Los filtros se añaden con parámetros; los fragmentos SQL son constantes
    // internas.
    @GetMapping
    public Object list(@RequestParam(required = false) Long saleId, @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (status != null && !Set.of("IN_PROGRESS", "READY", "DELIVERED", "CANCELLED", "RETURNED").contains(status))
            throw new ResponseStatusException(BAD_REQUEST, "Estado de orden inválido");
        if (from != null && to != null && !to.isAfter(from))
            throw new ResponseStatusException(BAD_REQUEST, "Intervalo inválido");
        String sql = "SELECT * FROM work_orders WHERE 1=1";
        var args = new ArrayList<Object>();
        if (saleId != null) {
            sql += " AND sale_id=?";
            args.add(saleId);
        }
        if (status != null) {
            sql += " AND status=?";
            args.add(status);
        }
        if (from != null) {
            sql += " AND promised_date>=?";
            args.add(from);
        }
        if (to != null) {
            sql += " AND promised_date<?";
            args.add(to);
        }
        sql += " ORDER BY promised_date,id LIMIT ? OFFSET ?";
        args.add(clinic.pageSize(size));
        args.add(clinic.offset(page, size));
        return db.queryForList(sql, args.toArray());
    }

    public record StatusInput(@NotBlank @Pattern(regexp = "IN_PROGRESS|READY") String status) {
    }

    @PatchMapping("/{id}/status")
    public Object status(@PathVariable long id, @Valid @RequestBody StatusInput input, Authentication auth) {
        return service.status(id, input.status(), clinic.userId(auth));
    }

}
