package com.opticaguinto.optica.auth.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import com.opticaguinto.optica.common.service.ClinicSupport;

@RestController
@RequestMapping("/api")
public class AuthController {

  private final JdbcTemplate db;
  private final ClinicSupport clinic;
  private final PasswordEncoder encoder;
  public AuthController(JdbcTemplate db, ClinicSupport clinic, PasswordEncoder encoder) { this.db=db; this.clinic=clinic; this.encoder=encoder; }


  // Devuelve el perfil y sus permisos sin exponer el hash de la contraseña.
  @GetMapping("/auth/me")
  public Object me(Authentication auth) {
    var profile = new LinkedHashMap<>(db.queryForMap(
        "SELECT id,first_name,paternal_surname,maternal_surname,email,active FROM users WHERE id=?", clinic.userId(auth)));
    profile.put("roles", auth.getAuthorities().stream().map(g -> g.getAuthority()).toList());
    return profile;
  }


  // Contrato para cambiar la contraseña propia; la actual se verifica antes de
  // guardar la nueva.
  public record PasswordInput(@NotBlank String currentPassword,
      @NotBlank @Size(min = 12, max = 72) String newPassword) {
  }


  @PutMapping("/auth/password")
  @ResponseStatus(NO_CONTENT)
  public void password(@Valid @RequestBody PasswordInput p, Authentication auth) {
    long id = clinic.userId(auth);
    String hash = db.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id);
    if (!encoder.matches(p.currentPassword(), hash))
      throw new ResponseStatusException(BAD_REQUEST, "Contraseña actual incorrecta");
    clinic.checkPassword(p.newPassword());
    db.update("UPDATE users SET password_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
        encoder.encode(p.newPassword()), id);
  }
}
