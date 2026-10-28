# Organización de los controllers

Los controllers están separados por responsabilidad y conservan las rutas `/api` existentes:

| Módulo | Controller | Responsabilidad |
|---|---|---|
| auth | AuthController | Perfil autenticado y cambio de contraseña |
| personnel | PersonnelController | Usuarios, roles, estado y directorio de profesionales |
| patient | PatientController | Registro, consulta, edición y archivado de pacientes |
| prescription | PrescriptionController | Recetas, historial y opciones de selección para ventas |
| appointment | AppointmentController | Agenda, reprogramación y cierre de citas |
| product | ProductController | Catálogo, ajustes e historial de inventario |
| sale | SaleController | Ventas, pagos, entrega, cancelación y devolución |

`ClinicController` y `CommerceController` fueron reemplazados. Las funciones de
reprogramación y selección de recetas salieron de `PersonnelController`.

`common/service/ClinicSupport` conserva temporalmente las consultas y validaciones
compartidas que antes estaban en `ClinicController`. Los módulos de caja, órdenes
y reportes dependen de este servicio en lugar de depender de un controller.

Esta etapa separa los controllers; todavía hay SQL y reglas de negocio en ellos.
El siguiente paso es extraer los DTO, los services transaccionales y los repositories
por módulo. Los records de entrada permanecen dentro de sus nuevos controllers
para conservar las validaciones y los contratos JSON durante esta separación.

Las pruebas existentes usan los nuevos controllers. `test-compile` comprueba la
compilación sin ejecutar las pruebas; la ejecución de estas requiere una base
PostgreSQL exclusiva para pruebas, como se describe en el README.
