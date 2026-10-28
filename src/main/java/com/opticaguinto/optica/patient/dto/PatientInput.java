package com.opticaguinto.optica.patient.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record PatientInput(
    @NotBlank @Size(max = 100) String firstName,
    @NotBlank @Size(max = 200) String lastName,
    @PastOrPresent LocalDate birthDate,
    @Size(max = 30) String phone,
    @Email @Size(max = 254) String email,
    @Size(max = 4000) String notes) { }
