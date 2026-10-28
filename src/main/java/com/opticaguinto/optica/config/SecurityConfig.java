package com.opticaguinto.optica.config;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.cors.*;

// Configuración de autenticación, permisos por método y acceso desde el frontend.
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  // BCrypt guarda un hash con sal; la contraseña original no se almacena.
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  // Spring Security consulta el usuario y sus roles al autenticar cada petición.
  @Bean
  UserDetailsService userDetailsService(JdbcTemplate db) {
    return email -> {
      // El correo se compara sin distinguir mayúsculas; una cuenta inactiva no puede
      // autenticarse.
      var rows = db.queryForList("SELECT * FROM users WHERE lower(email)=lower(?)", email);
      if (rows.isEmpty())
        throw new UsernameNotFoundException("Credenciales incorrectas");
      var u = rows.getFirst();
      var roles = db.queryForList("SELECT r.name FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=?",
          String.class, u.get("id"));
      return User.withUsername(u.get("email").toString()).password(u.get("password_hash").toString())
          .authorities(roles.toArray(String[]::new)).disabled(!Boolean.TRUE.equals(u.get("active"))).build();
    };
  }

  // Solo la comprobación de salud es pública. El resto requiere HTTP Basic y no
  // crea sesiones.
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    // CSRF está deshabilitado en esta configuración; CORS se define en el bean
    // siguiente.
    return http.cors(Customizer.withDefaults()).csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
        .httpBasic(Customizer.withDefaults()).build();
  }

  // Permite únicamente los orígenes, métodos y encabezados indicados para el
  // frontend.
  @Bean
  CorsConfigurationSource cors(@Value("${app.cors.origins}") String origins) {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(Arrays.asList(origins.split(",")));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  // Crea el administrador inicial usando variables de entorno cuando aún no hay
  // usuarios.
  @Bean
  CommandLineRunner bootstrap(JdbcTemplate db, PasswordEncoder encoder, TransactionTemplate tx,
      @Value("${app.admin.email}") String email, @Value("${app.admin.password}") String password) {
    return args -> {
      if (email.isBlank() || password.isBlank())
        return;
      if (password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
        throw new IllegalArgumentException("ADMIN_PASSWORD requiere al menos 12 caracteres y máximo 72 bytes UTF-8");
      // El usuario y su rol se guardan juntos; un fallo revierte ambas operaciones.
      tx.executeWithoutResult(s -> {
        if (db.queryForObject("SELECT count(*) FROM users", Long.class) > 0)
          return;
        Long id = db.queryForObject(
            "INSERT INTO users(first_name,paternal_surname,email,password_hash,email_verified) VALUES ('Administrador','Sistema',?,?,true) RETURNING id",
            Long.class, email.trim().toLowerCase(Locale.ROOT), encoder.encode(password));
        db.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='ROLE_ADMIN'", id);
      });
    };
  }
}
