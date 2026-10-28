package com.opticaguinto.optica.appointment.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import com.opticaguinto.optica.common.service.ClinicSupport;

@RestController
@RequestMapping("/api")
public class AppointmentController {

  private final JdbcTemplate db;
  private final ClinicSupport clinic;
  public AppointmentController(JdbcTemplate db, ClinicSupport clinic) { this.db=db; this.clinic=clinic; }


  // Los horarios incluyen offset y deben ser futuros al recibir la petición.
  public record AppointmentInput(@Positive long patientId, @Positive long optometristId,
      @NotNull @Future OffsetDateTime startsAt, @NotNull @Future OffsetDateTime endsAt,
      @Size(max = 1000) String notes) {
  }


  // Valida y registra la cita dentro de una transacción para proteger la reserva
  // del horario.
  @PostMapping("/appointments")
  @ResponseStatus(CREATED)
  @Transactional
  public Object appointment(@Valid @RequestBody AppointmentInput p) {
    clinic.activePatient(p.patientId());
    clinic.optometrist(p.optometristId());
    if (!p.endsAt().isAfter(p.startsAt()))
      throw new ResponseStatusException(BAD_REQUEST, "El fin debe ser posterior al inicio");
    // El bloqueo del profesional serializa las reservas concurrentes hasta terminar
    // la transacción.
    db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, p.optometristId());
    clinic.optometrist(p.optometristId());
    // Dos citas se cruzan si una empieza antes del fin de la otra y termina después
    // de su inicio.
    if (db.queryForObject(
        "SELECT count(*) FROM appointments WHERE optometrist_id=? AND status='SCHEDULED' AND starts_at < ? AND ends_at > ?",
        Long.class, p.optometristId(), p.endsAt(), p.startsAt()) > 0)
      throw new ResponseStatusException(CONFLICT, "El horario ya está ocupado");
    return clinic.one("appointments", db.queryForObject(
        "INSERT INTO appointments(patient_id,optometrist_id,starts_at,ends_at,notes) VALUES (?,?,?,?,?) RETURNING id",
        Long.class, p.patientId(), p.optometristId(), p.startsAt(), p.endsAt(), p.notes()));
  }


  // Filtra por inicio en el intervalo [from, to): incluye from y excluye to.
  @GetMapping("/appointments")
  public Object appointments(@RequestParam OffsetDateTime from, @RequestParam OffsetDateTime to,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return db.queryForList(
        "SELECT * FROM appointments WHERE starts_at>=? AND starts_at<? ORDER BY starts_at,id LIMIT ? OFFSET ?", from,
        to, clinic.pageSize(size), clinic.offset(page, size));
  }


  // Una cita programada puede completarse o cancelarse; no se permite reabrirla
  // por esta ruta.
  public record AppointmentStatus(@NotBlank @Pattern(regexp = "COMPLETED|CANCELLED") String status) {
  }


  // La condición en el UPDATE impide cerrar dos veces la misma cita, incluso con
  // solicitudes simultáneas.
  @PatchMapping("/appointments/{id}/status")
  public Object appointmentStatus(@PathVariable long id, @Valid @RequestBody AppointmentStatus s) {
    if (db.update("UPDATE appointments SET status=? WHERE id=? AND status='SCHEDULED'", s.status(), id) == 0) {
      clinic.one("appointments", id);
      throw new ResponseStatusException(CONFLICT, "La cita ya está cerrada");
    }
    return clinic.one("appointments", id);
  }

    // Bloquea la cita y el profesional destino antes de comprobar cruces de horario.
    @PutMapping("/appointments/{id}")
    @Transactional
    public Object reschedule(@PathVariable long id,@Valid @RequestBody AppointmentInput input) {
        var rows=db.queryForList("SELECT * FROM appointments WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Cita no encontrada");
        var appointment=rows.getFirst();
        if(!"SCHEDULED".equals(appointment.get("status")))
            throw new ResponseStatusException(CONFLICT,"Solo se reprograman citas pendientes");
        if(((Number)appointment.get("patient_id")).longValue()!=input.patientId())
            throw new ResponseStatusException(CONFLICT,"La reprogramación no puede cambiar al paciente");
        clinic.activePatient(input.patientId());
        if(!input.endsAt().isAfter(input.startsAt())) throw new ResponseStatusException(BAD_REQUEST,"Intervalo inválido");
        var users=db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",input.optometristId());
        if(users.isEmpty()) throw new ResponseStatusException(BAD_REQUEST,"Optometrista inválido");
        clinic.optometrist(input.optometristId());
        if(db.queryForObject("""
            SELECT count(*) FROM appointments WHERE id<>? AND optometrist_id=? AND status='SCHEDULED'
            AND starts_at<? AND ends_at>?
            """,Long.class,id,input.optometristId(),input.endsAt(),input.startsAt())>0)
            throw new ResponseStatusException(CONFLICT,"El horario ya está ocupado");
        db.update("UPDATE appointments SET optometrist_id=?,starts_at=?,ends_at=?,notes=? WHERE id=?",
            input.optometristId(),input.startsAt(),input.endsAt(),input.notes(),id);
        return clinic.one("appointments",id);
    }
}
