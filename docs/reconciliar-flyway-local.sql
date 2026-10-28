-- Ejecutar manualmente en pgAdmin, sobre optica_db, con el backend detenido.
-- Preparado para los archivos V3/V4/V5 actuales y el esquema revisado el 2026-10-23.
-- No ejecutar sobre otra base o un esquema diferente sin volver a compararlo.
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE public.flyway_schema_history IN EXCLUSIVE MODE;

DO $$
DECLARE missing_ids text;
BEGIN
  IF current_database() <> 'optica_db' THEN
    RAISE EXCEPTION 'Selecciona optica_db antes de ejecutar este script';
  END IF;
  IF (SELECT count(*) FROM public.flyway_schema_history) <> 2
     OR (SELECT count(*) FROM public.flyway_schema_history
         WHERE version IN ('1','2') AND success AND type='SQL') <> 2 THEN
    RAISE EXCEPTION 'El historial ya no contiene solamente V1 y V2 exitosas. Revisar antes de continuar';
  END IF;

  -- Las exportaciones compartidas no incluian is_identity; verificarlo aqui.
  SELECT string_agg(t.name, ', ') INTO missing_ids
  FROM (VALUES ('patients'),('products'),('prescriptions'),('appointments'),
               ('sales'),('sale_items'),('payments'),('stock_movements'),
               ('refunds'),('work_orders'),('work_order_events')) t(name)
  WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.columns c
    WHERE c.table_schema='public' AND c.table_name=t.name
      AND c.column_name='id' AND c.is_identity='YES'
      AND c.identity_generation='ALWAYS'
  );
  IF missing_ids IS NOT NULL THEN
    RAISE EXCEPTION 'Identidades distintas de las migraciones en: %', missing_ids;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='cash_days'
                   AND column_name='opening_cash') THEN
    RAISE EXCEPTION 'No se encontro la estructura de V5';
  END IF;
END $$;

CREATE TABLE public.flyway_history_backup_20261023 AS
SELECT * FROM public.flyway_schema_history;

-- Checksum CRC32 de las lineas UTF-8, sin terminadores de linea, como Flyway.
-- Es una reconciliacion manual: execution_time=0 no representa ejecucion del SQL.
INSERT INTO public.flyway_schema_history
  (installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
VALUES
  (3,'3','optical business','SQL','V3__optical_business.sql',-1704032477,current_user,0,true),
  (4,'4','work orders cash and reports','SQL','V4__work_orders_cash_and_reports.sql',443936744,current_user,0,true),
  (5,'5','cash opening balance','SQL','V5__cash_opening_balance.sql',1270802931,current_user,0,true);

COMMIT;

SELECT installed_rank,version,description,success
FROM public.flyway_schema_history ORDER BY installed_rank;

-- Si una comprobacion falla, ejecutar ROLLBACK; antes de otra consulta.
