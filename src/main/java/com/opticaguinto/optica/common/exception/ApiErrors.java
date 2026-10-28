package com.opticaguinto.optica.common.exception;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

// Convierte errores conocidos de los controladores en respuestas HTTP con mensajes para el cliente.
@RestControllerAdvice
public class ApiErrors {
    // Conserva el estado HTTP y el motivo indicado por las reglas de negocio.
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", e.getReason() == null ? "Solicitud incorrecta" : e.getReason()));
    }

    // Devuelve los campos que no cumplen las restricciones de los contratos de
    // entrada.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Datos inválidos", "errors", e.getBindingResult()
                .getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).toList()));
    }

    // Agrupa JSON mal formado, tipos incompatibles y parámetros que no se pueden
    // convertir.
    @ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
    ResponseEntity<?> malformed(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Formato de solicitud inválido"));
    }

    // No expone detalles SQL internos cuando se viola una restricción de la base de
    // datos.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e) {
        return ResponseEntity.status(409)
                .body(Map.of("message", "El registro está duplicado, tiene referencias o incumple una restricción"));
    }
}
