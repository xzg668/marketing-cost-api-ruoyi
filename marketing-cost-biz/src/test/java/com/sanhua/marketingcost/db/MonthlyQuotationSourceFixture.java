package com.sanhua.marketingcost.db;

import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.CompiledFormula;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.impl.OriginalLinkedRuleSnapshot;
import com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable MySQL source fixtures; prices are published through the real financial service. */
final class MonthlyQuotationSourceFixture {
  static void copperLinked(JdbcTemplate jdbc, PriceLinkedItemMapper rules,
      OriginalLinkedRuleSnapshot snapshots, FinanceEffectivePriceService effective,
      PlatformTransactionManager transactions, String code, String month, String unit) {
    String batch = UUID.randomUUID().toString();
    int revision = jdbc.queryForObject("SELECT COALESCE(MAX(publication_revision),0)+1 FROM lp_factor_upload_batch WHERE business_unit_type='COMMERCIAL' AND price_org_code='210' AND price_month=?", Integer.class, month);
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,'COMMERCIAL','MPP12-controlled.xlsx',?,?,'MPP12','PUBLISHED','210',?,'DEFAULT',NOW())",
        batch, month, "a".repeat(64), "b".repeat(64), revision);
    long upload = jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?", Long.class, batch);
    String copper = PriceFactorContext.COPPER;
    var identities = jdbc.queryForList("SELECT id FROM lp_factor_identity WHERE business_unit_type='COMMERCIAL' AND canonical_factor_key=? ORDER BY id", Long.class, copper);
    if (identities.isEmpty())
      jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES('COMMERCIAL',?,?,?,'AVG',?)", batch, copper, copper, copper);
    long identity = identities.isEmpty() ? jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?", Long.class, batch) : identities.getFirst();
    jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,100,1,?,'PUBLISHED','210','DEFAULT','GENERAL',?,'CNY/KG',NOW())", identity, month, upload, revision);
    long price = jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=? AND source_upload_batch_id=?", Long.class, identity, upload);
    jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'财务基价',3,?,?,4,'D3','DEFAULT','GENERAL','100','CNY/KG','0.000000','IDENTITY')", upload, identity, price);
    var key = new MaterialPriceRuleKey("COMMERCIAL", "210", code, "MPP12-S", "MPP12-S", null, unit);
    String expression = "cu/10";
    var factors = Map.of("cu", new OriginalLinkedWorkbookParser.FactorInput(copper,
        new Address("财务基价", "D3"), FactorPriceUnit.CNY_KG, "CNY/KG", copper, "SAME_CANONICAL_FACTOR"));
    var original = new OriginalLinkedWorkbookParser.Rule(key, "MPP12 联动料", "原稿", 8, "H8",
        expression, expression, "0.000000", false, new CompiledFormula(expression, List.of(), Map.of()), factors, BigDecimal.ZERO);
    var row = new PriceLinkedItem();
    row.setRuleIdentityKey(key.identityKey()); row.setRuleRevision(1); row.setRuleStatus("PUBLISHED");
    row.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION); row.setSourceKind("PUBLIC");
    row.setBusinessUnitType("COMMERCIAL"); row.setOrgCode("210"); row.setPricingMonth(month);
    row.setMaterialCode(code); row.setMaterialName("MPP12 联动料"); row.setUnit(unit);
    row.setSupplierCode("MPP12-S"); row.setSupplierName("MPP12-S"); row.setTaxIncluded(0);
    row.setFormulaExpr(expression); row.setSourceInputSnapshotJson(snapshots.write(List.of(original), "MPP12-controlled-original"));
    row.setSourceSheetName("原稿"); row.setSourceRowNumber(8); row.setSourceFormulaCellRef("H8");
    row.setSourceFormulaExpr("D3/10"); row.setEffectiveFrom(YearMonth.parse(month).atDay(1));
    row.setEffectiveTo(LocalDate.of(2099, 1, 1)); row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(LocalDateTime.now());
    row.setDeleted(0); row.setTechnicalRevision(0); rules.insert(row);
    new TransactionTemplate(transactions).executeWithoutResult(status -> effective.generateScope("COMMERCIAL", "210"));
  }
}
