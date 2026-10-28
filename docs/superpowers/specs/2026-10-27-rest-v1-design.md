# Migración REST compatible a API v1

## Intención

Publicar un contrato corregido bajo `/api/v1` sin romper a los clientes que hoy consumen `/api`. Se atenderán los hallazgos 2–6: transiciones como recursos, movimientos de inventario como recursos, paginación explícita, errores RFC 9457 y contrato OpenAPI versionado.

## Metas

- Mantener `/api` disponible sin cambios durante la adopción de v1.
- Representar entrega, cancelación, devolución y apertura/cierre de caja con recursos de dominio y operaciones HTTP coherentes.
- Hacer que `POST` de recursos creados en v1 devuelva `201`, el recurso creado y su `Location`.
- Validar `page` y `size`, devolver metadatos de paginación y rechazar intervalos inválidos con `400`.
- Usar `application/problem+json` con errores estables también para autenticación y autorización.
- Publicar OpenAPI 3.1 como contrato fuente y conservar una colección Postman generada para ejecución.

## No metas

- No retirar ni modificar las rutas `/api` en esta entrega.
- No cambiar HTTP Basic, los roles ni las reglas de negocio.
- No implantar HATEOAS completo, paginación por cursor, rate limiting o claves de idempotencia. Las operaciones POST repetidas siguen pudiendo duplicar la solicitud; se documentará esta limitación.
- No fijar todavía una fecha de retiro del API legado.

## Enfoques considerados

1. Corregir directamente `/api`: menor esfuerzo, pero puede romper clientes existentes.
2. Mezclar rutas nuevas y antiguas dentro de `/api`: reduce algunas roturas, pero deja un contrato ambiguo.
3. Mantener `/api` y publicar `/api/v1` en paralelo: elegido, porque da una ruta de adopción explícita y preserva clientes actuales.

## Contrato propuesto

### Transiciones de venta y caja

Las acciones crearán recursos inmutables con identidad, responsable, fecha y motivo cuando aplique:

- `POST /api/v1/sales/{saleId}/deliveries`: exige venta abierta, saldo liquidado y órdenes listas; crea la entrega y pasa venta/órdenes a `DELIVERED`.
- `POST /api/v1/sales/{saleId}/cancellations`: exige venta abierta; crea la cancelación, repone existencias y registra los reembolsos correspondientes.
- `POST /api/v1/sales/{saleId}/returns`: exige venta entregada; recibe el motivo y realiza devolución total.
- `POST /api/v1/cash/days/{date}/openings`: crea una apertura única con `openingCash`.
- `POST /api/v1/cash/days/{date}/closures`: crea un cierre único con `countedCash` y notas.

Cada creación devolverá `201`, cuerpo del recurso y `Location` consultable con GET. Restricciones únicas evitarán aperturas/cierres duplicados y producirán `409`. El resumen existente se expondrá como `GET /api/v1/cash/days/{date}`. Las rutas verbales existentes seguirán sirviendo bajo `/api` y no se duplicarán en v1.

### Movimientos de inventario

El nuevo endpoint será `POST /api/v1/products/{productId}/stock-movements`; responderá con el movimiento creado y Location `/api/v1/products/{productId}/stock-movements/{movementId}`. El listado usará el mismo sustantivo. Actualización de stock y creación del movimiento seguirán siendo atómicas; movimientos de ventas, cancelaciones y devoluciones aparecerán también en el historial.

### Paginación e intervalos

Las colecciones paginadas aceptarán `page >= 0` y `1 <= size <= 100`; por omisión `0` y `20`. Valores inválidos darán `400`. La respuesta será `{ items, page, size, total, hasNext, links }`, con enlaces `self`, y `prev`/`next` cuando correspondan. `total` respetará los filtros. Intervalos `from`/`to` deberán venir completos y cumplir `from < to`.

### Errores y trazabilidad

Todos los errores v1 usarán `application/problem+json` con `type`, `title`, `status`, `detail`, `instance`, código estable, timestamp y traceId. Errores de validación incluirán una lista de campos. Códigos iniciales: `VALIDATION_ERROR`, `MALFORMED_REQUEST`, `NOT_FOUND`, `CONFLICT`, `UNAUTHORIZED`, `FORBIDDEN` e `INTERNAL_ERROR`. Un filtro generará/propagará `X-Trace-Id`; Security usará el mismo formato para 401/403. No se expondrán SQL ni trazas.

### OpenAPI y representación

`openapi/optica-api-v1.yaml` será OpenAPI 3.1, fuente del contrato, con HTTP Basic, parámetros, cuerpos, errores, paginación y Location. Se servirá en `/openapi/v1.yaml`; la documentación interactiva se habilitará en desarrollo. Una colección Postman se generará desde OpenAPI para no mantener dos contratos manuales. DTOs v1 estabilizarán la representación JSON y evitarán exponer mapas `SELECT *`.

## Compatibilidad y retiro

`/api` conservará su comportamiento y se documentará como legado; `/api/v1` será la versión recomendada. No se agregan encabezados de deprecación hasta acordar fecha de retiro y medir consumidores. Antes del retiro se deberá anunciar fin de vida y emitir `Deprecation`/`Sunset` durante la transición.

## Implementación y verificación

Se extraerán servicios de dominio compartidos para preservar reglas y transacciones entre rutas. Una migración Flyway nueva añadirá los recursos persistentes de entrega/cancelación/devolución y apertura/cierre, con restricciones e índices; migraciones aplicadas no se editarán. Luego se agregarán DTOs, rutas, paginación, Problem Details, trazabilidad, OpenAPI y colección. La verificación cubrirá `Location`, estados y transacciones, entradas inválidas, paginación, 401/403 y validación del contrato. La prueba local no demostrará adopción por consumidores externos.

## Decisiones de implementación

El implementador elegirá una biblioteca OpenAPI compatible con la versión Spring Boot del proyecto, verificándola contra documentación oficial; definirá nombres Java internos y ajustará conteos/índices a PostgreSQL. El contrato JSON descrito aquí no se altera sin actualizar este diseño.
