# Backend de Óptica Guinto

API REST con Java 21, Spring Boot 4.1.1, Spring Security, JDBC y PostgreSQL.
Flyway conserva las migraciones V1–V3 y agrega órdenes, reembolsos y cortes en V4,
y el fondo de apertura de caja en V5.

## Ejecutar

Requiere JDK 21 y una base PostgreSQL creada con un usuario que pueda crear tablas.
Configura las variables del archivo .env.example en tu entorno o IDE. Spring Boot
no carga archivos .env automáticamente. Ejemplo en PowerShell:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/optica_db'
$env:DB_USERNAME = 'optica_user'
$env:DB_PASSWORD = 'tu-clave-de-base-de-datos'
$env:ADMIN_EMAIL = 'admin@optica.local'
$env:ADMIN_PASSWORD = 'una-clave-larga-y-segura'
.\mvnw.cmd spring-boot:run
```

La API queda en http://localhost:8080/api. GET /actuator/health es público.
El primer arranque crea el administrador únicamente si no existe ningún usuario
y se especifican ADMIN_EMAIL y ADMIN_PASSWORD. Después retira ambas variables.
No hay contraseñas predeterminadas. Las contraseñas requieren al menos 12 caracteres
y como máximo 72 bytes UTF-8; se guardan con BCrypt.

Autenticación HTTP Basic en cada petición: Authorization: Basic base64(email:password).
El frontend puede verificar las credenciales con GET /api/auth/me, que devuelve
el perfil y sus roles. No se emiten tokens ni sesiones. Utiliza HTTPS al desplegar.
CORS_ORIGINS es una lista separada por comas de los orígenes permitidos.

## Endpoints

Las rutas de la tabla son relativas a /api. A = administrador, O = optometrista,
V = vendedor. Las listas usan page=0 y size=20 (máximo 100) y devuelven arreglos.
Los campos JSON de entrada usan camelCase; los registros de salida usan snake_case.
Los identificadores son numéricos. Fechas civiles: YYYY-MM-DD; horarios: ISO 8601
con zona u offset, por ejemplo 2026-12-01T10:00:00-06:00.

| Método | Ruta | Acceso | Función |
|---|---|---|---|
| GET | /auth/me | A,O,V | Perfil y roles |
| PUT | /auth/password | A,O,V | currentPassword y newPassword |
| GET, POST | /users | A | Listar y crear usuarios |
| PUT | /users/{id} | A | Editar datos y roles; protege al último administrador activo |
| GET | /staff/optometrists | A,O,V | Directorio para agendar |
| PATCH | /users/{id}/status | A | Activar/desactivar con active; no permite desactivarse |
| GET, POST | /patients | A,O,V | Buscar con search y registrar pacientes |
| GET, PUT | /patients/{id} | A,O,V | Consultar/actualizar paciente |
| DELETE | /patients/{id} | A | Archivar sin eliminar historial |
| POST | /prescriptions | A,O | Crear receta, autor tomado de la autenticación |
| GET | /patients/{id}/prescriptions | A,O | Historial clínico paginado |
| GET | /patients/{id}/prescription-options | A,O,V | Seleccionar receta sin exponer graduación |
| GET | /appointments?from=...&to=... | A,O,V | Citas del intervalo [from,to) |
| POST | /appointments | A,O,V | Agendar sin solapar horarios del optometrista |
| PUT | /appointments/{id} | A,O,V | Reprogramar cita pendiente |
| PATCH | /appointments/{id}/status | A,O,V | COMPLETED o CANCELLED |
| GET | /products?search=... | A,O,V | Catálogo activo |
| POST | /products | A | Crear producto con existencias cero |
| PUT, DELETE | /products/{id} | A | Editar o archivar |
| POST | /products/{id}/stock | A | Ajuste firmado quantity y reason |
| GET | /products/{id}/movements | A | Historial de inventario |
| PATCH | /products/{id}/minimum-stock | A | Definir alerta de existencias mínimas |
| GET, POST | /sales | A,V | Listar/registrar ventas |
| GET | /sales/{id} | A,V | Venta, partidas y pagos |
| POST | /sales/{id}/payments | A,V | Abonar amount con method y reference |
| POST | /sales/{id}/deliver | A,V | Entregar venta liquidada con todas sus órdenes listas |
| POST | /sales/{id}/cancel | A | Cancelar, reembolsar abonos y reponer inventario |
| POST | /sales/{id}/refunds | A | Reembolso parcial de un cobro en venta abierta |
| POST | /sales/{id}/return | A | Devolución completa de una venta entregada |
| GET, POST | /work-orders | A,O,V | Consultar/crear órdenes de lentes |
| GET, PUT | /work-orders/{id} | A,O,V | Consultar/editar orden en elaboración |
| PATCH | /work-orders/{id}/status | A,O,V | Elaboración o lista; entrega desde la venta |
| GET | /cash/days/{date} | A,V | Totales del día por método y corte guardado |
| POST | /cash/days/{date}/open | A,V | Fondo de apertura antes del primer movimiento |
| POST | /cash/days/{date}/close | A | Corte diario con efectivo contado y diferencia |
| GET | /reports/summary | A | Resumen; from/to opcionales |
| GET | /reports/sales-by-seller | A | Ventas por vendedor; from/to requeridos |
| GET | /reports/top-products | A | Productos más vendidos; from/to requeridos |
| GET | /reports/patient-balances | A,V | Saldos por paciente; from/to opcionales |
| GET | /reports/low-stock | A,V | Productos activos en o bajo su mínimo |

Errores: 400 datos inválidos, 401 credenciales ausentes/incorrectas, 403 permisos
insuficientes, 404 registro inexistente, 409 duplicados o conflicto de negocio.

## Ejemplos de cuerpos JSON

Crear usuario (roles válidos: ROLE_ADMIN, ROLE_OPTOMETRIST, ROLE_SELLER):

```json
{"firstName":"Ana","paternalSurname":"Guinto","maternalSurname":null,"email":"ana@optica.local","password":"UnaClaveSegura123!","roles":["ROLE_OPTOMETRIST"]}
```

Paciente:

```json
{"firstName":"María","lastName":"López","birthDate":"1995-04-20","phone":"9511234567","email":"maria@example.com","notes":""}
```

Producto (category: FRAME, LENS, CONTACT_LENS o ACCESSORY):

```json
{"sku":"AR-001","name":"Armazón clásico","category":"FRAME","price":850.00}
```

Entrada de inventario:

```json
{"quantity":10,"reason":"Recepción de proveedor"}
```

Receta:

```json
{"patientId":1,"examinedOn":"2026-10-22","rightSphere":-1.25,"rightCylinder":-0.5,"rightAxis":90,"leftSphere":-1,"leftCylinder":-0.25,"leftAxis":80,"addition":0,"pupillaryDistance":62,"notes":""}
```

Cita:

```json
{"patientId":1,"optometristId":2,"startsAt":"2026-12-01T10:00:00-06:00","endsAt":"2026-12-01T10:30:00-06:00","notes":""}
```

Venta y abono, respectivamente:

```json
{"patientId":1,"items":[{"productId":1,"quantity":2}]}
```

```json
{"amount":500.00,"method":"TRANSFER","reference":"transferencia-123"}
```

## Reglas implementadas

Los precios de venta se toman del catálogo y se conservan en las partidas.
Los productos se bloquean en orden de identificador durante la venta; existencias,
venta y movimientos se confirman juntos o se revierten juntos. Las partidas
repetidas se agrupan. Los pagos bloquean la venta para impedir sobrepagos
concurrentes. El pagado de la venta es neto de reembolsos. No se puede entregar con
saldo pendiente ni con órdenes en elaboración. La cancelación reembolsa los abonos
remanentes y repone existencias una sola vez; la devolución completa hace lo mismo
para una venta entregada. Los cobros originales y reembolsos se conservan por separado.
Las citas bloquean al optometrista al validar cruces de horario. Se permiten citas
consecutivas y únicamente profesionales activos con rol ADMIN u OPTOMETRIST.

Consulta [la guía de operación](docs/operacion-api.md) para cuerpos JSON de los
nuevos módulos, estados, reglas de caja y criterios de los reportes.

## Pruebas y empaquetado

Las pruebas requieren una base PostgreSQL **exclusiva para pruebas**: borran los
registros de pacientes, productos, citas, recetas, ventas, pagos, reembolsos,
órdenes, cortes de caja y movimientos, y el personal de prueba distinto del administrador.
No apuntes TEST_DB_URL a una base con información real.
Por defecto usan 127.0.0.1:55432/optica_test, usuario postgres.
Puedes configurar TEST_DB_URL, TEST_DB_USERNAME y TEST_DB_PASSWORD.

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

Se prueban autenticación y permisos por HTTP, validación, inventario insuficiente,
pagos, entrega, cancelación, órdenes, devoluciones, caja, roles, reprogramación,
reportes y concurrencia sobre las migraciones PostgreSQL reales.
El JAR ejecutable se genera en target/optica-backend-0.0.1-SNAPSHOT.jar.

## Alcance

Esta implementación cubre una sucursal con una caja diaria. No incluye
facturación fiscal, descuentos/impuestos, devoluciones parciales de productos,
retiros/gastos de caja, recuperación de contraseña por correo, verificación de
email ni pasarela de pagos. Cobros y reembolsos registran operaciones efectuadas
manualmente. No se proporcionó un frontend: la integración deberá usar el contrato
documentado.
