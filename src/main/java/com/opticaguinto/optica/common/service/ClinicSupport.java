package com.opticaguinto.optica.common.service;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import com.opticaguinto.optica.patient.service.PatientService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@org.springframework.stereotype.Service
public class ClinicSupport {
  private final JdbcTemplate db;
  private final PatientService patientService;
  public ClinicSupport(JdbcTemplate db, PatientService patientService) { this.db = db; this.patientService = patientService; }


  // Obtiene el identificador desde la identidad autenticada, sin confiar en un ID
  // enviado por el cliente.
  public long userId(Authentication auth) {
    return db.queryForObject("SELECT id FROM users WHERE email=?", Long.class, auth.getName());
  }


  // Los nombres de tabla deben ser constantes internas, nunca valores recibidos
  // en una petición.
  public Map<String, Object> one(String table, long id) {
    var rows = db.queryForList("SELECT * FROM " + table + " WHERE id=?", id);
    if (rows.isEmpty())
      throw new ResponseStatusException(NOT_FOUND, "Registro no encontrado");
    return rows.getFirst();
  }


  // Impide nuevas operaciones clínicas o ventas para pacientes archivados.
  public void activePatient(long id) {
    patientService.requireActive(id);
  }


  // Limita las listas a un máximo de 100 registros y normaliza los valores de
  // paginación.
  public int pageSize(int size) {
    return Math.max(1, Math.min(size, 100));
  }


  public long offset(int page, int size) {
    return (long) Math.max(page, 0) * pageSize(size);
  }


  // La agenda solo acepta profesionales activos con rol de administrador u
  // optometrista.
  public void optometrist(long id) {
    if (db.queryForObject(
        "SELECT count(*) FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.id=? AND u.active=true AND r.name IN ('ROLE_ADMIN','ROLE_OPTOMETRIST')",
        Long.class, id) == 0)
      throw new ResponseStatusException(BAD_REQUEST, "Optometrista inválido");
  }


  // BCrypt admite hasta 72 bytes: los caracteres con acentos pueden ocupar más de
  // un byte.
  public void checkPassword(String value) {
    if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new ResponseStatusException(BAD_REQUEST, "La contraseña no puede superar 72 bytes UTF-8");
  }


  // Serializa cambios de personal para que dos solicitudes no eliminen al último administrador activo.
  public void lockPersonnel() { db.queryForList("SELECT pg_advisory_xact_lock(72510)"); }

  public void requireAdministrator() {
    if(db.queryForObject("""
        SELECT count(*) FROM users u JOIN user_roles ur ON ur.user_id=u.id
        JOIN roles r ON r.id=ur.role_id WHERE u.active AND r.name='ROLE_ADMIN'
        """,Long.class)==0)
      throw new ResponseStatusException(CONFLICT,"Debe permanecer al menos un administrador activo");
  }
}
