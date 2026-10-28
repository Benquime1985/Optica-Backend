package com.opticaguinto.optica;
import java.math.BigDecimal;
import com.opticaguinto.optica.common.service.ClinicSupport;
import com.opticaguinto.optica.appointment.controller.AppointmentController;
import com.opticaguinto.optica.product.controller.ProductController;
import com.opticaguinto.optica.sale.controller.SaleController;
import java.time.OffsetDateTime;
import java.util.*;
import java.net.URI;
import java.net.http.*;

import com.opticaguinto.optica.cash.service.CashService;
import com.opticaguinto.optica.personnel.controller.PersonnelController;
import com.opticaguinto.optica.report.controller.ReportController;
import com.opticaguinto.optica.workorder.service.WorkOrderService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

// Pruebas con servidor HTTP real y una base PostgreSQL exclusiva para pruebas.
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://127.0.0.1:55432/optica_test}",
 "spring.datasource.username=${TEST_DB_USERNAME:postgres}","spring.datasource.password=${TEST_DB_PASSWORD:}",
 "app.admin.email=admin@test.local","app.admin.password=TestPassword123!"})
class OpticaBackendApplicationTests {
 @Autowired ClinicSupport clinic;
 @Autowired AppointmentController appointments;
 @Autowired ProductController products;
 @Autowired SaleController commerce;
 @Autowired JdbcTemplate db;
 @Autowired
 WorkOrderService workOrders;
 @Autowired
 CashService cash;
 @Autowired
 PersonnelController personnel;
 @Autowired
 ReportController reports;
 @Value("${local.server.port}") int port;
 final UsernamePasswordAuthenticationToken admin=new UsernamePasswordAuthenticationToken("admin@test.local","",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
 long patient;
 long product;
 // Limpia las tablas de prueba respetando las claves foráneas; no usar una base con datos reales.
 @BeforeEach void setup() {
  SecurityContextHolder.getContext().setAuthentication(admin);
  db.update("DELETE FROM work_order_events"); db.update("DELETE FROM work_orders");
  db.update("DELETE FROM refunds"); db.update("DELETE FROM cash_close_totals"); db.update("DELETE FROM cash_days");
  db.update("DELETE FROM payments"); db.update("DELETE FROM stock_movements"); db.update("DELETE FROM sale_items");
  db.update("DELETE FROM sales"); db.update("DELETE FROM appointments"); db.update("DELETE FROM prescriptions"); db.update("DELETE FROM products"); db.update("DELETE FROM patients");
  db.update("DELETE FROM users WHERE email<>'admin@test.local'");
  patient=db.queryForObject("INSERT INTO patients(first_name,last_name) VALUES ('Ana','Guinto') RETURNING id",Long.class);
  product=db.queryForObject("INSERT INTO products(sku,name,category,price,stock) VALUES ('A1','Armazon','FRAME',100.50,5) RETURNING id",Long.class);
 }
 // Limpia la autenticación para no afectar la siguiente prueba.
 @AfterEach void clear() { SecurityContextHolder.clearContext(); }
 // Registra una venta con los datos preparados para la prueba.
 long sale(int quantity) {
  var result=(Map<?,?>)commerce.sale(new SaleController.SaleInput(patient,List.of(new SaleController.Line(product,quantity))),admin);
  return ((Number)result.get("id")).longValue();
 }
 // Comprueba existencias, sobrepagos y entrega de ventas liquidadas.
 @Test void salePaymentsAndDelivery() {
  long id=sale(2);
  assertEquals(3,db.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));
  assertThrows(ResponseStatusException.class,()->commerce.deliver(id,admin));
  commerce.payment(id,new SaleController.PaymentInput(new BigDecimal("201.00")),admin);
  assertThrows(ResponseStatusException.class,()->commerce.payment(id,new SaleController.PaymentInput(BigDecimal.ONE),admin));
  commerce.deliver(id,admin);
  assertEquals("DELIVERED",db.queryForObject("SELECT status FROM sales WHERE id=?",String.class,id));
  assertThrows(ResponseStatusException.class,()->commerce.cancel(id,admin));
 }
 // Una partida sin existencias debe impedir toda la venta.
 @Test void insufficientStockRollsBackEntireSale() {
  long other=db.queryForObject("INSERT INTO products(sku,name,category,price,stock) VALUES ('A2','Otro','FRAME',10,0) RETURNING id",Long.class);
  assertThrows(ResponseStatusException.class,()->commerce.sale(new SaleController.SaleInput(patient,List.of(new SaleController.Line(product,2),new SaleController.Line(other,1))),admin));
  assertEquals(5,db.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));
  assertEquals(0,db.queryForObject("SELECT count(*) FROM sales",Integer.class));
 }
 // Cancelar dos veces no debe duplicar la devolución de inventario.
 @Test void cancellationRestoresStockOnlyOnce() {
  long id=sale(2); commerce.cancel(id,admin);
  assertThrows(ResponseStatusException.class,()->commerce.cancel(id,admin));
  assertEquals(5,db.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));
 }
 // Dos compras simultáneas compiten por el mismo producto; solo una debe confirmarse.
 @Test void concurrentSalesCannotOversell() throws Exception {
  // Sincroniza ambos hilos para iniciar las compras al mismo tiempo.
  var ready=new java.util.concurrent.CountDownLatch(2);
  var start=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.Callable<Boolean> purchase=() -> {
   // Cada hilo necesita su propio contexto de seguridad.
   SecurityContextHolder.getContext().setAuthentication(admin);
   ready.countDown(); start.await();
   try { sale(4); return true; }
   catch(ResponseStatusException e) { assertEquals(409,e.getStatusCode().value()); return false; }
   finally { SecurityContextHolder.clearContext(); }
  };
  try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   var first=executor.submit(purchase); var second=executor.submit(purchase);
   assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
   assertNotEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
  }
  assertEquals(1,db.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));
  assertEquals(1,db.queryForObject("SELECT count(*) FROM sales",Integer.class));
 }
 // Rechaza horarios cruzados y permite citas consecutivas.
 @Test void overlappingAppointmentRejected() {
  var start=OffsetDateTime.now().plusDays(1);
  long opt=clinic.userId(admin);
  appointments.appointment(new AppointmentController.AppointmentInput(patient,opt,start,start.plusHours(1),null));
  assertThrows(ResponseStatusException.class,()->appointments.appointment(new AppointmentController.AppointmentInput(patient,opt,start.plusMinutes(30),start.plusHours(2),null)));
  appointments.appointment(new AppointmentController.AppointmentInput(patient,opt,start.plusHours(1),start.plusHours(2),null));
 }
 // Envía peticiones HTTP para comprobar autenticación y validaciones.
 HttpResponse<String> request(String path,String credentials,String body) throws Exception {
  var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
  if(credentials!=null) b.header("Authorization","Basic "+Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  if(body!=null) b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
  return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
 }
 // Verifica credenciales incorrectas, entradas inválidas y permisos del vendedor.
 @Test void httpSecurityAndValidation() throws Exception {
  assertEquals(401,request("/api/patients",null,null).statusCode());
  assertEquals(401,request("/api/patients","admin@test.local:wrong",null).statusCode());
  assertEquals(200,request("/api/auth/me","admin@test.local:TestPassword123!",null).statusCode());
  assertEquals(400,request("/api/patients","admin@test.local:TestPassword123!","{\"firstName\":\"\"}").statusCode());
  personnel.createUser(new PersonnelController.UserInput("Vendedor","Prueba",null,"seller@test.local","SellerPassword123!",Set.of("ROLE_SELLER")));
  assertEquals(403,request("/api/users","seller@test.local:SellerPassword123!",null).statusCode());
 }

 long prescription(long patientId) {
  return db.queryForObject("INSERT INTO prescriptions(patient_id,optometrist_id,examined_on) VALUES (?,?,CURRENT_DATE) RETURNING id",
    Long.class,patientId,clinic.userId(admin));
 }
 WorkOrderService.OrderInput orderInput(long saleId,long prescriptionId) {
  return new WorkOrderService.OrderInput(saleId,prescriptionId,"Progresivo","Resina",new BigDecimal("1.60"),
    "Antirreflejante",null,"Laboratorio de prueba",cash.today().plusDays(5),null);
 }
 // Una receta ajena no se puede usar, y la preparación del laboratorio condiciona la entrega.
 @Test void workOrderLifecycleAndPrescriptionOwnership() {
  long id=sale(1);
  long other=db.queryForObject("INSERT INTO patients(first_name,last_name) VALUES ('Otro','Paciente') RETURNING id",Long.class);
  assertThrows(ResponseStatusException.class,()->workOrders.create(orderInput(id,prescription(other)),clinic.userId(admin)));
  var order=(Map<?,?>)workOrders.create(orderInput(id,prescription(patient)),clinic.userId(admin));
  long orderId=((Number)order.get("id")).longValue();
  commerce.payment(id,new SaleController.PaymentInput(new BigDecimal("100.50")),admin);
  assertThrows(ResponseStatusException.class,()->commerce.deliver(id,admin));
  workOrders.status(orderId,"READY",clinic.userId(admin));
  commerce.deliver(id,admin);
  assertEquals("DELIVERED",db.queryForObject("SELECT status FROM work_orders WHERE id=?",String.class,orderId));
  assertEquals(3,db.queryForObject("SELECT count(*) FROM work_order_events WHERE work_order_id=?",Integer.class,orderId));
  assertThrows(ResponseStatusException.class,()->workOrders.edit(orderId,orderInput(id,prescription(patient)),clinic.userId(admin)));
 }
 // El cobro original queda intacto y el saldo neto disminuye con cada reembolso.
 @Test void partialRefundAndCancellationKeepAuditTrail() {
  long id=sale(1);
  commerce.payment(id,new SaleController.PaymentInput(new BigDecimal("90"),"CARD","voucher-1"),admin);
  long payment=db.queryForObject("SELECT id FROM payments WHERE sale_id=?",Long.class,id);
  commerce.refund(id,new SaleController.RefundInput(payment,new BigDecimal("20"),"CARD","refund-1","Corrección"),admin);
  assertEquals(new BigDecimal("70.00"),db.queryForObject("SELECT paid FROM sales WHERE id=?",BigDecimal.class,id));
  assertThrows(ResponseStatusException.class,()->commerce.refund(id,
    new SaleController.RefundInput(payment,new BigDecimal("71"),"CASH",null,"Exceso"),admin));
  commerce.cancel(id,admin);
  assertEquals(new BigDecimal("90.00"),db.queryForObject("SELECT amount FROM payments WHERE id=?",BigDecimal.class,payment));
  assertEquals(new BigDecimal("90.00"),db.queryForObject("SELECT sum(amount) FROM refunds WHERE sale_id=?",BigDecimal.class,id));
  assertEquals(new BigDecimal("0.00"),db.queryForObject("SELECT paid FROM sales WHERE id=?",BigDecimal.class,id));
  assertEquals(5,db.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));
  assertThrows(ResponseStatusException.class,()->commerce.cancel(id,admin));
 }
 @Test void fullReturnRefundsDeliveredSaleOnlyOnce() {
  long id=sale(2);
  commerce.payment(id,new SaleController.PaymentInput(new BigDecimal("201")),admin);
  commerce.deliver(id,admin);
  commerce.returnSale(id,new SaleController.ReturnInput("Devolución total"),admin);
  assertEquals("RETURNED",db.queryForObject("SELECT status FROM sales WHERE id=?",String.class,id));
  assertEquals(5,db.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));
  assertThrows(ResponseStatusException.class,()->commerce.returnSale(id,new SaleController.ReturnInput("Repetida"),admin));
  assertEquals(new BigDecimal("201.00"),db.queryForObject("SELECT sum(amount) FROM refunds WHERE sale_id=?",BigDecimal.class,id));
 }
 @Test void dailyCloseSeparatesMethodsAndRejectsFurtherMovements() {
  long id=sale(2);
  commerce.payment(id,new SaleController.PaymentInput(new BigDecimal("100"),"CASH",null),admin);
  commerce.payment(id,new SaleController.PaymentInput(new BigDecimal("50"),"TRANSFER","bank-1"),admin);
  long payment=db.queryForObject("SELECT id FROM payments WHERE sale_id=? AND method='CASH'",Long.class,id);
  commerce.refund(id,new SaleController.RefundInput(payment,new BigDecimal("20"),"CASH",null,"Abono corregido"),admin);
  cash.close(cash.today(),new BigDecimal("79"),"Falta un peso",clinic.userId(admin));
  var close=db.queryForMap("SELECT * FROM cash_days WHERE business_date=?",cash.today());
  assertEquals(new BigDecimal("80.00"),close.get("expected_cash"));
  assertEquals(new BigDecimal("-1.00"),close.get("difference"));
  assertEquals(new BigDecimal("50.00"),db.queryForObject("SELECT net FROM cash_close_totals WHERE business_date=? AND method='TRANSFER'",BigDecimal.class,cash.today()));
  assertThrows(ResponseStatusException.class,()->cash.close(cash.today(),BigDecimal.ZERO,null,clinic.userId(admin)));
  assertThrows(ResponseStatusException.class,()->commerce.payment(id,new SaleController.PaymentInput(BigDecimal.ONE),admin));
  assertThrows(ResponseStatusException.class,()->commerce.cancel(id,admin));
  assertEquals("OPEN",db.queryForObject("SELECT status FROM sales WHERE id=?",String.class,id));
  assertEquals(3,db.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));
 }
 @Test void reschedulingExcludesItselfAndRejectsOtherAppointments() {
  var start=OffsetDateTime.now().plusDays(2);
  long user=clinic.userId(admin);
  var first=(Map<?,?>)appointments.appointment(new AppointmentController.AppointmentInput(patient,user,start,start.plusMinutes(30),null));
  long id=((Number)first.get("id")).longValue();
  appointments.appointment(new AppointmentController.AppointmentInput(patient,user,start.plusHours(1),start.plusHours(2),null));
  appointments.reschedule(id,new AppointmentController.AppointmentInput(patient,user,start,start.plusMinutes(30),"Actualizada"));
  assertThrows(ResponseStatusException.class,()->appointments.reschedule(id,
    new AppointmentController.AppointmentInput(patient,user,start.plusHours(1),start.plusHours(2),null)));
  appointments.reschedule(id,new AppointmentController.AppointmentInput(patient,user,start.plusHours(3),start.plusHours(4),null));
  appointments.appointmentStatus(id,new AppointmentController.AppointmentStatus("CANCELLED"));
  assertThrows(ResponseStatusException.class,()->appointments.reschedule(id,
    new AppointmentController.AppointmentInput(patient,user,start.plusHours(5),start.plusHours(6),null)));
 }
 @Test void personnelEditsProtectLastAdministratorAndExposeOnlyDirectory() throws Exception {
  long user=clinic.userId(admin);
  assertThrows(ResponseStatusException.class,()->personnel.edit(user,
    new PersonnelController.EditInput("Admin","Prueba",null,"admin@test.local",Set.of("ROLE_SELLER"))));
  assertEquals(1,db.queryForObject("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.name='ROLE_ADMIN'",Integer.class,user));
  var seller=(Map<?,?>)personnel.createUser(new PersonnelController.UserInput("Recepción","Prueba",null,
    "seller@test.local","SellerPassword123!",Set.of("ROLE_SELLER")));
  long sellerId=((Number)seller.get("id")).longValue();
  personnel.edit(sellerId,new PersonnelController.EditInput("Profesional","Prueba",null,
    "seller@test.local",Set.of("ROLE_SELLER","ROLE_OPTOMETRIST")));
  var directory=request("/api/staff/optometrists","seller@test.local:SellerPassword123!",null);
  assertEquals(200,directory.statusCode());
  assertTrue(directory.body().contains("Profesional"));
  assertFalse(directory.body().contains("email"));
  assertFalse(directory.body().contains("password"));
 }
 @Test void reportsUseSaleDatesAndExcludeReturns() {
  long first=sale(1); long second=sale(2);
  var date=cash.today();
  var zone=java.time.ZoneId.of("America/Mexico_City");
  db.update("UPDATE sales SET created_at=? WHERE id=?",date.atStartOfDay(zone).plusHours(10).toOffsetDateTime(),first);
  db.update("UPDATE sales SET created_at=? WHERE id=?",date.minusDays(1).atStartOfDay(zone).plusHours(10).toOffsetDateTime(),second);
  var summary=(Map<?,?>)reports.summary(date,date.plusDays(1));
  assertEquals(1L,summary.get("sales"));
  assertEquals(new BigDecimal("100.50"),summary.get("total"));
  var sellers=(List<?>)reports.bySeller(date,date.plusDays(1),0,20);
  assertEquals(new BigDecimal("100.50"),((Map<?,?>)sellers.getFirst()).get("total"));
  var products=(List<?>)reports.topProducts(date,date.plusDays(1),0,20);
  assertEquals(1L,((Map<?,?>)products.getFirst()).get("quantity"));
  var balances=(List<?>)reports.balances(null,null,0,20);
  assertEquals(new BigDecimal("301.50"),((Map<?,?>)balances.getFirst()).get("pending"));
  commerce.payment(first,new SaleController.PaymentInput(new BigDecimal("100.50")),admin);
  commerce.deliver(first,admin);
  commerce.returnSale(first,new SaleController.ReturnInput("Devolución"),admin);
  assertEquals(0L,((Map<?,?>)reports.summary(date,date.plusDays(1))).get("sales"));
  assertThrows(ResponseStatusException.class,()->reports.summary(date,date));
 }
 @Test void lowStockUsesProductThresholdAndOmitsArchivedProducts() {
  assertTrue(((List<?>)reports.lowStock(0,20)).isEmpty());
  reports.minimum(product,new ReportController.MinimumStockInput(5));
  assertEquals(1,((List<?>)reports.lowStock(0,20)).size());
  products.archiveProduct(product);
  assertTrue(((List<?>)reports.lowStock(0,20)).isEmpty());
 }
 @Test void paymentMetadataValidationAndModulePermissions() throws Exception {
  long id=sale(1);
  String credentials="admin@test.local:TestPassword123!";
  assertEquals(400,request("/api/sales/"+id+"/payments",credentials,
    "{\"amount\":10,\"method\":\"CARD\"}").statusCode());
  assertEquals(400,request("/api/sales/"+id+"/payments",credentials,
    "{\"amount\":10,\"method\":\"OTHER\"}").statusCode());
  assertEquals(201,request("/api/sales/"+id+"/payments",credentials,
    "{\"amount\":10,\"method\":\"CARD\",\"reference\":\"voucher-http\"}").statusCode());
  personnel.createUser(new PersonnelController.UserInput("Vendedor","Prueba",null,
    "seller@test.local","SellerPassword123!",Set.of("ROLE_SELLER")));
  String seller="seller@test.local:SellerPassword123!";
  assertEquals(403,request("/api/sales/"+id+"/refunds",seller,
    "{\"paymentId\":1,\"amount\":1,\"method\":\"CASH\",\"reason\":\"Prueba\"}").statusCode());
  assertEquals(403,request("/api/cash/days/"+cash.today()+"/close",seller,"{\"countedCash\":10}").statusCode());
  assertEquals(403,request("/api/reports/sales-by-seller?from=2026-01-01&to=2027-01-01",seller,null).statusCode());
 }
 // Los dos reembolsos se validan bajo el mismo bloqueo de venta: no pueden consumir el mismo abono.
 @Test void concurrentRefundsCannotExceedOriginalPayment() throws Exception {
  long id=sale(1);
  commerce.payment(id,new SaleController.PaymentInput(new BigDecimal("100")),admin);
  long payment=db.queryForObject("SELECT id FROM payments WHERE sale_id=?",Long.class,id);
  var ready=new java.util.concurrent.CountDownLatch(2);
  var start=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.Callable<Boolean> refund=() -> {
   SecurityContextHolder.getContext().setAuthentication(admin);
   ready.countDown();
   try {
    assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));
    commerce.refund(id,new SaleController.RefundInput(payment,new BigDecimal("60"),"CASH",null,"Prueba concurrente"),admin);
    return true;
   } catch(ResponseStatusException e) { assertEquals(409,e.getStatusCode().value()); return false; }
   finally { SecurityContextHolder.clearContext(); }
  };
  try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   var first=executor.submit(refund); var second=executor.submit(refund);
   try { assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)); } finally { start.countDown(); }
   assertNotEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
  }
  assertEquals(new BigDecimal("40.00"),db.queryForObject("SELECT paid FROM sales WHERE id=?",BigDecimal.class,id));
  assertEquals(new BigDecimal("60.00"),db.queryForObject("SELECT sum(amount) FROM refunds WHERE sale_id=?",BigDecimal.class,id));
 }
 @Test void cashOpeningBalanceIsIncludedAndCannotBeChangedAfterMovements() {
  cash.open(cash.today(),new BigDecimal("500"),clinic.userId(admin));
  long id=sale(1);
  commerce.payment(id,new SaleController.PaymentInput(new BigDecimal("100")),admin);
  assertThrows(ResponseStatusException.class,()->cash.open(cash.today(),BigDecimal.ONE,clinic.userId(admin)));
  cash.close(cash.today(),new BigDecimal("600"),null,clinic.userId(admin));
  assertEquals(new BigDecimal("600.00"),db.queryForObject("SELECT expected_cash FROM cash_days WHERE business_date=?",BigDecimal.class,cash.today()));
  assertEquals(new BigDecimal("0.00"),db.queryForObject("SELECT difference FROM cash_days WHERE business_date=?",BigDecimal.class,cash.today()));
 }
 // Si la reposición no cabe en el inventario, también se revierten reembolsos y cierre de venta.
 @Test void returnFailureRollsBackRefundsAndSaleState() {
  long id=sale(1);
  commerce.payment(id,new SaleController.PaymentInput(new BigDecimal("100.50")),admin);
  commerce.deliver(id,admin);
  db.update("UPDATE products SET stock=2147483647 WHERE id=?",product);
  assertThrows(ResponseStatusException.class,()->commerce.returnSale(id,new SaleController.ReturnInput("Prueba"),admin));
  assertEquals("DELIVERED",db.queryForObject("SELECT status FROM sales WHERE id=?",String.class,id));
  assertEquals(new BigDecimal("100.50"),db.queryForObject("SELECT paid FROM sales WHERE id=?",BigDecimal.class,id));
  assertEquals(0,db.queryForObject("SELECT count(*) FROM refunds WHERE sale_id=?",Integer.class,id));
 }
}
