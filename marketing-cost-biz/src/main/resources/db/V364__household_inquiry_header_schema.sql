-- Header-based workbooks retain their actual worksheet and all original cells.
ALTER TABLE lp_household_inquiry_import_batch
  DROP CHECK ck_household_batch_parser,
  ADD CONSTRAINT ck_household_batch_parser CHECK (
    (parser_schema='HOUSEHOLD_COIL_V1' AND worksheet_name='10月线圈询价')
    OR (parser_schema='HOUSEHOLD_COIL_V2' AND CHAR_LENGTH(TRIM(worksheet_name))>0));
