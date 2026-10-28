package com.opticaguinto.optica.patient.controller;
import jakarta.validation.Valid;
import com.opticaguinto.optica.patient.dto.PatientInput;
import com.opticaguinto.optica.patient.dto.PatientResponse;
import com.opticaguinto.optica.patient.service.PatientService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.opticaguinto.optica.common.web.CreatedResource;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api")
public class PatientController {

  private final PatientService patients;
  public PatientController(PatientService patients) { this.patients = patients; }


  // Busca pacientes activos por nombre, apellidos o teléfono mediante parámetros
  // SQL.
  @GetMapping("/patients")
  public Object patients(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return patients.search(search, page, size).stream().map(PatientResponse::from).toList();
  }


  // La consulta individual también permite recuperar pacientes archivados para
  // consultar su historial.
  @GetMapping("/patients/{id}")
  public Object patient(@PathVariable long id) {
    return PatientResponse.from(patients.get(id));
  }


  // PostgreSQL devuelve el ID generado con RETURNING; la respuesta contiene el
  // registro creado.
  @PostMapping("/patients")
  public ResponseEntity<PatientResponse> createPatient(@Valid @RequestBody PatientInput p) {
    var patient = patients.create(p);
    return CreatedResource.at("/api/patients/{id}", patient.getId(), PatientResponse.from(patient));
  }


  // Comprueba que el paciente exista y sustituye sus datos editables.
  @PutMapping("/patients/{id}")
  public Object updatePatient(@PathVariable long id, @Valid @RequestBody PatientInput p) {
    return PatientResponse.from(patients.update(id, p));
  }


  // El archivado es lógico: conserva las recetas, citas y ventas relacionadas.
  @DeleteMapping("/patients/{id}")
  @ResponseStatus(NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void archivePatient(@PathVariable long id) {
    patients.archive(id);
  }
}
