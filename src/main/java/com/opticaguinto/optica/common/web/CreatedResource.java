package com.opticaguinto.optica.common.web;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

public final class CreatedResource {
    private CreatedResource() {}

    public static <T> ResponseEntity<T> at(String path, long id, T body) {
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(path).buildAndExpand(id).toUri();
        return ResponseEntity.created(location).body(body);
    }

    public static <T> ResponseEntity<T> child(long id, T body) {
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(id).toUri();
        return ResponseEntity.created(location).body(body);
    }
}
