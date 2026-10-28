package com.opticaguinto.optica.patient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.opticaguinto.optica.patient.model.Patient;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PatientResponse(
    long id,
    @JsonProperty("first_name") String firstName,
    @JsonProperty("last_name") String lastName,
    @JsonProperty("birth_date") LocalDate birthDate,
    String phone,
    String email,
    String notes,
    boolean active,
    @JsonProperty("created_at") OffsetDateTime createdAt) {
  public static PatientResponse from(Patient patient) {
    return new PatientResponse(patient.getId(), patient.getFirstName(), patient.getLastName(),
        patient.getBirthDate(), patient.getPhone(), patient.getEmail(), patient.getNotes(),
        patient.isActive(), patient.getCreatedAt());
  }
}
