package com.opticaguinto.optica.prescription.controller;
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
import static org.springframework.http.HttpStatus.*;
import com.opticaguinto.optica.common.service.ClinicSupport;

@RestController
@RequestMapping("/api")
public class PrescriptionController {

  private final JdbcTemplate db;
  private final ClinicSupport clinic;
  public PrescriptionController(JdbcTemplate db, ClinicSupport clinic) { this.db=db; this.clinic=clinic; }


  // Graduación de ambos ojos: esfera y cilindro, ejes en grados, adición y
  // distancia pupilar.
  public record PrescriptionInput(@Positive long patientId, @NotNull @PastOrPresent LocalDate examinedOn,
      @DecimalMin("-30") @DecimalMax("30") BigDecimal rightSphere,
      @DecimalMin("-15") @DecimalMax("15") BigDecimal rightCylinder, @Min(0) @Max(180) Integer rightAxis,
      @DecimalMin("-30") @DecimalMax("30") BigDecimal leftSphere,
      @DecimalMin("-15") @DecimalMax("15") BigDecimal leftCylinder, @Min(0) @Max(180) Integer leftAxis,
      @DecimalMin("0") @DecimalMax("6") BigDecimal addition,
      @DecimalMin("20") @DecimalMax("90") BigDecimal pupillaryDistance, @Size(max = 4000) String notes) {
  }


  // La receta se atribuye al profesional autenticado; el cliente no puede elegir
  // otro autor.
  @PostMapping("/prescriptions")
  @ResponseStatus(CREATED)
  @PreAuthorize("hasAnyRole('ADMIN','OPTOMETRIST')")
  @Transactional
  public Object prescription(@Valid @RequestBody PrescriptionInput p, Authentication a) {
    clinic.activePatient(p.patientId());
    long id = db.queryForObject(
        "INSERT INTO prescriptions(patient_id,optometrist_id,examined_on,right_sphere,right_cylinder,right_axis,left_sphere,left_cylinder,left_axis,addition,pupillary_distance,notes) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
        Long.class, p.patientId(), clinic.userId(a), p.examinedOn(), p.rightSphere(), p.rightCylinder(), p.rightAxis(),
        p.leftSphere(), p.leftCylinder(), p.leftAxis(), p.addition(), p.pupillaryDistance(), p.notes());
    return clinic.one("prescriptions", id);
  }


  // El historial de recetas se reserva a administradores y optometristas.
  @GetMapping("/patients/{id}/prescriptions")
  @PreAuthorize("hasAnyRole('ADMIN','OPTOMETRIST')")
  public Object prescriptions(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    clinic.one("patients", id);
    return db.queryForList(
        "SELECT * FROM prescriptions WHERE patient_id=? ORDER BY examined_on DESC,id DESC LIMIT ? OFFSET ?", id,
        clinic.pageSize(size), clinic.offset(page, size));
  }

    // Permite seleccionar la receta de una orden desde ventas sin mostrar la graduación ni las notas clínicas.
    @GetMapping("/patients/{id}/prescription-options")
    public Object prescriptionOptions(@PathVariable long id,@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size) {
        clinic.one("patients",id);
        return db.queryForList("""
            SELECT p.id,p.examined_on,p.optometrist_id,u.first_name,u.paternal_surname
            FROM prescriptions p JOIN users u ON u.id=p.optometrist_id WHERE p.patient_id=?
            ORDER BY p.examined_on DESC,p.id DESC LIMIT ? OFFSET ?
            """,id,clinic.pageSize(size),clinic.offset(page,size));
    }
}
