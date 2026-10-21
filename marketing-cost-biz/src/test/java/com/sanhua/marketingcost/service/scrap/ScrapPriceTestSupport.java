package com.sanhua.marketingcost.service.scrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 财务发布相关回归使用真实废料同步依赖，空废料表也验证已接通的生产入口。 */
public final class ScrapPriceTestSupport {
  private ScrapPriceTestSupport() {}

  public static ScrapPriceCalculationEvidence.Snapshot captured(
      String month, String financialCu, String customerCuPerTon) {
    var json = new ObjectMapper().findAndRegisterModules();
    var rules = new ScrapPriceRuleCodec(json);
    var calculator = new ScrapPriceCalculator(rules);
    var row = new com.sanhua.marketingcost.entity.ScrapPrice();
    row.setId(91L);
    row.setBusinessUnitType("COMMERCIAL");
    row.setPriceMonth(month);
    row.setRuleSourceMonth(month);
    row.setRuleRevision(1);
    row.setRowVersion(1L);
    row.setImportBatchNo("SCRAP-TEST");
    rules.write(
        new ScrapPriceRule(
            "SCRAP",
            "废铜",
            "",
            "KG",
            null,
            null,
            "LINKED",
            null,
            "[Cu]*0.982/1.13",
            "[Cu]*0.982/1.13",
            java.util.Map.of("Cu", "AVG|1#CU")),
        row);
    var financial =
        new com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot(
            new com.sanhua.marketingcost.service.pricing.FactorPriceScope(
                "COMMERCIAL",
                "210",
                month,
                "DEFAULT",
                com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition.GENERAL),
            71L,
            1,
            "finance.xlsx",
            "file",
            "content",
            java.util.Map.of(
                "AVG|1#CU",
                new com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot.FactorValue(
                    1L,
                    2L,
                    "铜",
                    "Cu",
                    new java.math.BigDecimal(financialCu),
                    "CNY/KG",
                    true,
                    java.util.List.of())),
            java.util.List.of("DEFAULT"),
            "test",
            null,
            null);
    var publicInput = calculator.prepare(row, financial);
    row.setInputFingerprint(publicInput.fingerprint());
    var factors = publicInput.factors();
    if (customerCuPerTon != null)
      factors = factors.withCustomerCopperPerTon(new java.math.BigDecimal(customerCuPerTon));
    var input = calculator.prepare(row, factors);
    var selected =
        new com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService.Selection(
            null, null, "SINGLE", "单候选", null);
    return new ScrapPriceCalculationEvidence(json)
        .capture(
            row,
            "220",
            customerCuPerTon == null
                ? com.sanhua.marketingcost.enums.QuotePriceScenarioType.FINANCE_QUOTE_BASE
                : com.sanhua.marketingcost.enums.QuotePriceScenarioType.OA_LOCKED,
            input,
            calculator.calculate(input),
            selected);
  }

  public static ScrapPriceGenerationService service(
      SqlSessionTemplate sql, PlatformTransactionManager transactions, ObjectMapper json) {
    var identities = sql.getMapper(FactorIdentityMapper.class);
    var batches = sql.getMapper(FactorUploadBatchMapper.class);
    var finance =
        new FinanceFactorPriceReader(
            batches,
            sql.getMapper(FactorMonthlyPriceMapper.class),
            identities,
            sql.getMapper(FactorRowRefMapper.class));
    var rules = new ScrapPriceRuleCodec(json);
    return new ScrapPriceGenerationService(
        sql.getMapper(ScrapPriceMapper.class),
        identities,
        batches,
        finance,
        rules,
        new ScrapPriceCalculator(rules),
        transactions);
  }
}
