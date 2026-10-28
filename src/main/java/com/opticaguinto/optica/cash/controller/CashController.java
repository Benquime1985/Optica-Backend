package com.opticaguinto.optica.cash.controller;

import com.opticaguinto.optica.cash.service.CashService;
import com.opticaguinto.optica.common.service.ClinicSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cash")
public class CashController {
    private final CashService cash;
    private final ClinicSupport clinic;
    public CashController(CashService cash, ClinicSupport clinic) { this.cash=cash; this.clinic=clinic; }

    // Recepción/ventas puede revisar el día; solo administración confirma el corte.
    @GetMapping("/days/{date}")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Object day(@PathVariable LocalDate date) { return cash.day(date); }

    public record OpenInput(@NotNull @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal openingCash) {}
    @PostMapping("/days/{date}/open")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER')")
    public Object open(@PathVariable LocalDate date,@Valid @RequestBody OpenInput input,Authentication auth) {
        return cash.open(date,input.openingCash(),clinic.userId(auth));
    }

    public record CloseInput(@NotNull @DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal countedCash,
                             @Size(max=1000) String notes) {}
    @PostMapping("/days/{date}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public Object close(@PathVariable LocalDate date,@Valid @RequestBody CloseInput input,Authentication auth) {
        return cash.close(date,input.countedCash(),input.notes(),clinic.userId(auth));
    }
}
