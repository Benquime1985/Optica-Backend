# Operación de órdenes, caja, agenda y reportes

Todas las rutas parten de /api y requieren HTTP Basic.
ADMIN administra usuarios, cortes, cancelaciones y reembolsos.
SELLER puede agendar, operar ventas, órdenes y apertura de caja.
OPTOMETRIST puede operar agenda y órdenes; los reportes financieros son de administración.
Las respuestas de registros conservan snake_case y las entradas utilizan camelCase.
Las listas admiten page y size, con un máximo de 100 filas.

## Órdenes de lentes

1. Crear la venta del paciente.
2. Seleccionar una receta mediante GET /patients/{id}/prescription-options.
3. Crear la orden con POST /work-orders:

```json
{
  "saleId": 1,
  "prescriptionId": 3,
  "lensType": "Progresivo",
  "material": "Resina",
  "refractiveIndex": 1.60,
  "coating": "Antirreflejante",
  "tint": "Sin tinte",
  "laboratory": "Laboratorio Central",
  "promisedDate": "2026-12-05",
  "notes": "Verificar montaje"
}
```

La receta debe pertenecer al mismo paciente que la venta. Una venta puede tener
varias órdenes. La orden empieza en IN_PROGRESS (en elaboración).
El índice de refracción, tratamiento, tinte y notas son opcionales.
El índice admite dos decimales. La fecha prometida puede ser pasada para registrar
o corregir trabajos atrasados.

PUT /work-orders/{id} usa el mismo cuerpo y permite editar solo mientras está en
IN_PROGRESS, sin cambiar la venta. PATCH /work-orders/{id}/status recibe:

```json
{"status":"READY"}
```

READY significa listo; puede volver a IN_PROGRESS para corregir el trabajo.
Para entregar se usa POST /sales/{id}/deliver, sin cuerpo: exige saldo cero y todas
las órdenes en READY; cambia la venta y sus órdenes a DELIVERED (entregado).
Una venta sin órdenes conserva su flujo de entrega normal.
Una orden entregada no se edita ni reabre.

GET /work-orders permite filtrar por saleId, status, from y to.
Las fechas filtran la fecha prometida en el intervalo [from,to).
GET /work-orders/{id} incluye el historial de cambios de estado con responsable y fecha.
GET /sales/{id} incluye las órdenes, pagos y reembolsos.
La cancelación/devolución de la venta también marca sus órdenes como CANCELLED/RETURNED.

## Cobros, reembolsos y devoluciones

POST /sales/{id}/payments:

```json
{"amount":500.00,"method":"CARD","reference":"voucher-123"}
```

Métodos nuevos: CASH, CARD y TRANSFER. Tarjeta y transferencia requieren referencia;
efectivo puede omitirla. El método es obligatorio para peticiones HTTP.
Los importes deben ser positivos y tener como máximo dos decimales.
Solo se reciben cobros en ventas OPEN y hasta liquidar el total.
La fecha de operación se asigna en el servidor.

POST /sales/{id}/refunds, solo administración y con la venta OPEN:

```json
{"paymentId":5,"amount":100.00,"method":"CASH","reference":null,"reason":"Corrección de abono"}
```

Puede reembolsarse parte o todo el remanente de ese cobro. La suma de reembolsos
nunca supera su importe original, incluso con solicitudes simultáneas.
El cobro original se conserva; sales.paid representa cobros menos reembolsos.
Por tanto, reembolsar una venta abierta aumenta su saldo pendiente.
El medio elegido es el utilizado realmente para devolver el dinero.

POST /sales/{id}/cancel no necesita cuerpo. Solo administración puede cancelar una
venta OPEN. La operación reembolsa los cobros remanentes por su método original,
repone todas las partidas y marca la venta CANCELLED, junto con sus órdenes.
El motivo guardado es «Cancelación de venta».

POST /sales/{id}/return recibe:

```json
{"reason":"Cliente devuelve el pedido completo"}
```

Solo administración y ventas DELIVERED. La devolución es **completa**: devuelve el
remanente de todos los cobros por el método original, repone todas las unidades y
marca venta y órdenes RETURNED. El motivo y el responsable quedan registrados.
No se permite devolver o cancelar dos veces. Cualquier fallo revierte también
reembolsos, inventario y estados.

Estas rutas registran dinero devuelto manualmente; no ejecutan operaciones bancarias.
Los reembolsos automáticos conservan la referencia del cobro original para rastrearlo.
Las ventas entregadas se reembolsan mediante la devolución completa; no admiten
reembolsos parciales ni devolución por partidas.

## Caja diaria

BUSINESS_ZONE define la zona del día operativo; por defecto America/Mexico_City.
Existe una caja para toda la sucursal y un solo corte por fecha.

Al comenzar, POST /cash/days/2026-12-01/open:

```json
{"openingCash":500.00}
```

La apertura es opcional: si no se registra antes del primer cobro/reembolso, se
considera fondo cero. Solo se abre el día actual, una vez y antes de los movimientos.
El fondo no cuenta como una venta ni un cobro.

GET /cash/days/2026-12-01 muestra collected, refunded y net por método.
POST /cash/days/2026-12-01/close, solo administración:

```json
{"countedCash":1300.00,"notes":"Corte al terminar el turno"}
```

El corte guarda una fotografía de los totales y calcula:

- Efectivo esperado = fondo de apertura + cobros CASH − reembolsos CASH.
- Diferencia = efectivo contado − efectivo esperado.

El día cerrado no acepta nuevos cobros ni reembolsos y no se reabre por la API.
Las devoluciones de ventas de días anteriores se registran en el día actual,
sin modificar su corte original. Si el día actual ya está cerrado, esas operaciones
se rechazan; deben realizarse durante un día operativo abierto.
La cancelación sin cobros puede completarse sin movimiento monetario.
No hay cortes futuros, retiros, gastos ni múltiples turnos.

Los cobros previos a esta ampliación conservan método UNSPECIFIED, ya que no se
registró su medio real. Se muestran aparte y no se atribuyen a efectivo.
Sus fechas operativas se migran usando America/Mexico_City.

## Agenda y personal

GET /staff/optometrists devuelve identificador y nombres de profesionales activos.
Recepción/ventas puede consultar este directorio sin acceder al listado administrativo
de usuarios. Por compatibilidad con la agenda previa también incluye administradores
activos que pueden atender citas.

PUT /appointments/{id} utiliza los mismos datos que la creación:

```json
{"patientId":1,"optometristId":2,"startsAt":"2026-12-01T11:00:00-06:00","endsAt":"2026-12-01T11:30:00-06:00","notes":"Horario solicitado por el paciente"}
```

Solo se reprograman citas SCHEDULED y no se cambia el paciente.
El profesional debe estar activo y autorizado; se revisan cruces excluyendo la propia
cita. Las fechas deben ser futuras. Las citas completadas/canceladas no se reabren.

PUT /users/{id}, solo administración:

```json
{"firstName":"Ana","paternalSurname":"Guinto","maternalSurname":null,"email":"ana@optica.local","roles":["ROLE_OPTOMETRIST","ROLE_SELLER"]}
```

Reemplaza los datos y el conjunto de roles, sin cambiar la contraseña.
Se requiere al menos un rol válido. GET /users incluye los roles de cada usuario.
Los cambios de roles/estado se serializan y nunca pueden dejar al sistema sin un
administrador activo. Los nuevos permisos se aplican al autenticar la siguiente petición.

## Reportes

from y to son fechas YYYY-MM-DD. from se incluye y to se excluye; para consultar
todo diciembre se usa from=2026-12-01&to=2027-01-01.
Ambas se interpretan al comienzo del día en BUSINESS_ZONE.
Si se envía una fecha, debe enviarse también la otra.

| Ruta | Fechas | Criterio |
|---|---|---|
| /reports/summary | Opcionales | Cantidad, total, cobrado neto y saldo de ventas activas |
| /reports/sales-by-seller | Obligatorias | Cantidad e importes agrupados por vendedor |
| /reports/top-products | Obligatorias | Unidades e importe al precio histórico, ordenados por unidades |
| /reports/patient-balances | Opcionales | Saldos pendientes agrupados por paciente, ordenados por deuda |
| /reports/low-stock | No aplica | Productos activos con stock menor o igual al mínimo |

Los reportes de ventas filtran por **fecha de creación de la venta**, no por fecha
de abono. Reflejan el estado actual de esas ventas; no reconstruyen saldos de un día
pasado. Excluyen ventas CANCELLED y RETURNED. Para cobros y reembolsos por fecha
operativa se consultan los días de caja.

PATCH /products/{id}/minimum-stock recibe:

```json
{"minimumStock":3}
```

El mínimo inicial es cero, de modo que los productos agotados aparecen como alerta.
Las alertas se consultan por API; no se envían notificaciones automáticas.
