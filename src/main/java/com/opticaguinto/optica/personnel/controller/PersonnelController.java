package com.opticaguinto.optica.personnel.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import com.opticaguinto.optica.common.service.ClinicSupport;

@RestController
@RequestMapping("/api")
public class PersonnelController {

  private final JdbcTemplate db;
  private final ClinicSupport clinic;
  private final PasswordEncoder encoder;
  public PersonnelController(JdbcTemplate db, ClinicSupport clinic, PasswordEncoder encoder) { this.db=db; this.clinic=clinic; this.encoder=encoder; }


  // El estado desactiva el acceso sin eliminar al usuario ni sus registros
  // históricos.
  public record UserStatus(@NotNull Boolean active) {
  }


  // Evita que el administrador pierda el acceso desactivando su propia cuenta.
  @PatchMapping("/users/{id}/status")
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public Object userStatus(@PathVariable long id, @Valid @RequestBody UserStatus s, Authentication auth) {
    clinic.lockPersonnel();
    if (id == clinic.userId(auth) && !s.active())
      throw new ResponseStatusException(CONFLICT, "No puedes desactivar tu propia cuenta");
    if (db.update("UPDATE users SET active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", s.active(), id) == 0)
      throw new ResponseStatusException(NOT_FOUND, "Usuario no encontrado");
    clinic.requireAdministrator();
    return Map.of("id", id, "active", s.active());
  }


  // Datos de alta de personal. Solo se permiten los tres roles definidos en las
  // migraciones.
  public record UserInput(@NotBlank @Size(max = 100) String firstName,
      @NotBlank @Size(max = 100) String paternalSurname,
      @Size(max = 100) String maternalSurname, @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 72) String password,
      @NotEmpty Set<@NotNull @Pattern(regexp = "ROLE_ADMIN|ROLE_OPTOMETRIST|ROLE_SELLER") String> roles) {
  }


  // La transacción garantiza que la cuenta y todos sus roles se creen como una
  // sola operación.
  @PostMapping("/users")
  @ResponseStatus(CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public Object createUser(@Valid @RequestBody UserInput u) {
    clinic.checkPassword(u.password());
    long id = db.queryForObject(
        "INSERT INTO users(first_name,paternal_surname,maternal_surname,email,password_hash) VALUES (?,?,?,?,?) RETURNING id",
        Long.class, u.firstName(), u.paternalSurname(), u.maternalSurname(), u.email().trim().toLowerCase(Locale.ROOT),
        encoder.encode(u.password()));
    for (String role : u.roles())
      db.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?", id, role);
    return Map.of("id", id);
  }


  // Lista el personal con paginación y excluye los datos de autenticación.
  @GetMapping("/users")
  @PreAuthorize("hasRole('ADMIN')")
  public Object users(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    var result = db.queryForList(
        "SELECT id,first_name,paternal_surname,maternal_surname,email,active FROM users ORDER BY id LIMIT ? OFFSET ?",
        clinic.pageSize(size), clinic.offset(page, size));
    for(var user:result) user.put("roles",db.queryForList(
        "SELECT r.name FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.name",
        String.class,user.get("id")));
    return result;
  }

    // La agenda necesita nombres e identificadores, sin exponer correos ni datos de autenticación.
    @GetMapping("/staff/optometrists")
    public Object optometrists(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return db.queryForList("""
            SELECT u.id,u.first_name,u.paternal_surname,u.maternal_surname FROM users u
            WHERE u.active AND EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                WHERE ur.user_id=u.id AND r.name IN ('ROLE_ADMIN','ROLE_OPTOMETRIST'))
            ORDER BY u.first_name,u.id LIMIT ? OFFSET ?
            """,clinic.pageSize(size),clinic.offset(page,size));
    }

    public record EditInput(@NotBlank @Size(max=100) String firstName,
        @NotBlank @Size(max=100) String paternalSurname,@Size(max=100) String maternalSurname,
        @NotBlank @Email @Size(max=254) String email,
        @NotEmpty Set<@NotNull @Pattern(regexp="ROLE_ADMIN|ROLE_OPTOMETRIST|ROLE_SELLER") String> roles) {}


    @PutMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Object edit(@PathVariable long id,@Valid @RequestBody EditInput input) {
        clinic.lockPersonnel();
        clinic.one("users",id);
        db.update("""
            UPDATE users SET first_name=?,paternal_surname=?,maternal_surname=?,email=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
            """,input.firstName(),input.paternalSurname(),input.maternalSurname(),input.email().trim().toLowerCase(Locale.ROOT),id);
        db.update("DELETE FROM user_roles WHERE user_id=?",id);
        for(String role:input.roles())
            db.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?",id,role);
        clinic.requireAdministrator();
        var user=new LinkedHashMap<>(db.queryForMap("SELECT id,first_name,paternal_surname,maternal_surname,email,active FROM users WHERE id=?",id));
        user.put("roles",input.roles());
        return user;
    }
}
