package com.opticaguinto.optica.product.controller;
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
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import com.opticaguinto.optica.common.service.ClinicSupport;
import com.opticaguinto.optica.common.web.CreatedResource;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api")
public class ProductController {

  private final JdbcTemplate db;
  private final ClinicSupport clinic;
  public ProductController(JdbcTemplate db, ClinicSupport clinic) { this.db=db; this.clinic=clinic; }


    // El precio admite dos decimales y la categoría se restringe a los tipos del
    // catálogo.
    public record ProductInput(@NotBlank @Size(max = 80) String sku, @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "FRAME|LENS|CONTACT_LENS|ACCESSORY") String category,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal price) {
    }


    // Devuelve únicamente productos activos y busca por nombre o SKU con
    // paginación.
    @GetMapping("/products")
    public Object products(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String q = "%" + search.toLowerCase(Locale.ROOT) + "%";
        return db.queryForList(
                "SELECT * FROM products WHERE active=true AND (lower(name) LIKE ? OR lower(sku) LIKE ?) ORDER BY id LIMIT ? OFFSET ?",
                q, q, clinic.pageSize(size), clinic.offset(page, size));
    }


    // Los productos nacen sin existencias; las entradas se registran mediante
    // ajustes de inventario.
    @PostMapping("/products")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> product(@Valid @RequestBody ProductInput p) {
        long id = db.queryForObject("INSERT INTO products(sku,name,category,price,stock) VALUES (?,?,?,?,0) RETURNING id",
                        Long.class, p.sku(), p.name(), p.category(), p.price());
        return CreatedResource.at("/api/products/{id}", id, clinic.one("products", id));
    }

    @GetMapping("/products/{id}")
    public Object product(@PathVariable long id) { return clinic.one("products", id); }


    // Edita el catálogo sin alterar el precio histórico guardado en ventas
    // anteriores.
    @PutMapping("/products/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Object editProduct(@PathVariable long id, @Valid @RequestBody ProductInput p) {
        clinic.one("products", id);
        db.update("UPDATE products SET sku=?,name=?,category=?,price=? WHERE id=?", p.sku(), p.name(), p.category(),
                p.price(), id);
        return clinic.one("products", id);
    }


    // Archiva el producto sin eliminar sus partidas de venta ni sus movimientos.
    @DeleteMapping("/products/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(NO_CONTENT)
    public void archiveProduct(@PathVariable long id) {
        if (db.update("UPDATE products SET active=false WHERE id=?", id) == 0)
            throw new ResponseStatusException(NOT_FOUND, "Producto no encontrado");
    }


    // Una cantidad positiva representa una entrada; una negativa representa una
    // salida.
    public record StockInput(@NotNull Integer quantity, @NotBlank @Size(max = 200) String reason) {
    }


    // Historial paginado de ajustes, ventas y cancelaciones para revisar las
    // existencias.
    @GetMapping("/products/{id}/movements")
    @PreAuthorize("hasRole('ADMIN')")
    public Object movements(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        clinic.one("products", id);
        return db.queryForList("SELECT * FROM stock_movements WHERE product_id=? ORDER BY id DESC LIMIT ? OFFSET ?", id,
                clinic.pageSize(size), clinic.offset(page, size));
    }


    // El ajuste y su movimiento se confirman juntos o se revierten juntos.
    @PostMapping("/products/{id}/stock")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Object stock(@PathVariable long id, @Valid @RequestBody StockInput s, Authentication a) {
        if (s.quantity() == 0)
            throw new ResponseStatusException(BAD_REQUEST, "La cantidad no puede ser cero");
        clinic.one("products", id);
        // El UPDATE valida el saldo de forma atómica; bigint permite comprobar el rango
        // antes de sumar.
        if (db.update("UPDATE products SET stock=stock+? WHERE id=? AND stock::bigint+? BETWEEN 0 AND 2147483647",
                s.quantity(), id, s.quantity()) == 0)
            throw new ResponseStatusException(CONFLICT, "Existencias insuficientes o fuera de rango");
        movement(id, s.quantity(), s.reason(), clinic.userId(a));
        return clinic.one("products", id);
    }


    // Registra cantidad, motivo y responsable. La transacción corresponde al método
    // que lo invoca.
    void movement(long product, int quantity, String reason, long user) {
        db.update("INSERT INTO stock_movements(product_id,quantity,reason,user_id) VALUES (?,?,?,?)", product, quantity,
                reason, user);
    }
}
