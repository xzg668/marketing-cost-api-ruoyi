-- Optional source choice belongs to this OA decision; existing decisions keep their original rules.
SET @order_source_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='lp_quote_order_material_shape' AND column_name='selected_source_type'),
  'SELECT 1',
  'ALTER TABLE lp_quote_order_material_shape ADD COLUMN selected_source_type VARCHAR(32) NULL AFTER selected_shape,
    ADD CONSTRAINT ck_order_shape_source CHECK(selected_source_type IS NULL OR (selected_shape IS NOT NULL AND (
      (selected_source_type IN (''FIXED'',''LINKED'',''RANGE'',''TIER'',''ORDER'',''INVOICE'',''INQUIRY'',''HOUSEHOLD_INQUIRY'',''SETTLE_FIXED'',''SETTLE_LINKED'',''SCRAP'')
        AND selected_shape IN (''PURCHASE'',''OUTSOURCE'')) OR
      (selected_source_type=''MAKE'' AND selected_shape=''MANUFACTURE''))))'
);
PREPARE order_source_statement FROM @order_source_ddl;
EXECUTE order_source_statement;
DEALLOCATE PREPARE order_source_statement;
