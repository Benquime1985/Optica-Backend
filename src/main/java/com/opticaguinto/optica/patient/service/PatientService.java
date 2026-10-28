package com.opticaguinto.optica.patient.service;

import com.opticaguinto.optica.patient.dto.PatientInput;
import com.opticaguinto.optica.patient.model.Patient;
import com.opticaguinto.optica.patient.repository.PatientRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PatientService {
  private final PatientRepository patients;

  public PatientService(PatientRepository patients) { this.patients = patients; }

  @Transactional(readOnly = true)
  public List<Patient> search(String search, int page, int size) {
    return patients.searchActive(search,
        PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100)), Sort.by(Sort.Direction.DESC, "id")))
        .getContent();
  }

  @Transactional(readOnly = true)
  public Patient get(long id) {
    return patients.findById(id).orElseThrow(() -> notFound());
  }

  @Transactional
  public Patient create(PatientInput input) {
    return patients.saveAndFlush(new Patient(input.firstName(), input.lastName(), input.birthDate(), input.phone(),
        input.email(), input.notes()));
  }

  @Transactional
  public Patient update(long id, PatientInput input) {
    Patient patient = get(id);
    patient.update(input.firstName(), input.lastName(), input.birthDate(), input.phone(), input.email(), input.notes());
    return patient;
  }

  @Transactional
  public void archive(long id) { get(id).archive(); }

  @Transactional(readOnly = true)
  public void requireActive(long id) {
    if (!get(id).isActive())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Paciente inactivo");
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Paciente no encontrado");
  }
}
