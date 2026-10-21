package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ScrapPriceTestFixture {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final ScrapPriceRuleCodec rules = new ScrapPriceRuleCodec(json);
  final ScrapPriceCalculator calculator = spy(new ScrapPriceCalculator(rules));
  final ScrapPriceCalculationEvidence evidence = new ScrapPriceCalculationEvidence(json);
  final ScrapPriceScopeReader scopes = mock(ScrapPriceScopeReader.class);
  final SupplierSupplyRatioMapper ratios = mock(SupplierSupplyRatioMapper.class);
  final SupplierPriceDecisionMapper decisions = mock(SupplierPriceDecisionMapper.class);
  final OaFormMapper forms = mock(OaFormMapper.class);
  final ScrapPriceSelectionService service =
      new ScrapPriceSelectionService(
          scopes,
          new SupplierPriceSelectionService(ratios, decisions),
          forms,
          calculator,
          evidence);

  protected void load(List<ScrapPrice> rows) {
    when(scopes.read(anyString(), anyString(), anyBoolean()))
        .thenReturn(new ScrapPriceScopeReader.Scope(Set.of("scrap"), Map.of("scrap", rows)));
  }

  protected ScrapPriceSelectionService.Selection select(
      String org, PricePrepareScenarioContext scenario, boolean prepare) {
    return service
        .select(
            "COMMERCIAL",
            "2026-10",
            "scrap",
            org,
            "OA",
            LocalDate.of(2026, 10, 19),
            scenario,
            prepare,
            false)
        .orElseThrow();
  }

  protected PricePrepareScenarioContext finance() {
    return new PricePrepareScenarioContext(
        QuotePriceScenarioType.FINANCE_QUOTE_BASE,
        "group",
        "prepare",
        Map.of("Cu", new BigDecimal("90")),
        new SupplierPriceReviewContext(7L).forOrganization("220"));
  }

  protected ScrapPrice row(String supplierCode, String supplierName, String copper, String fixed) {
    var row = new ScrapPrice();
    row.setId(1L);
    row.setBusinessUnitType("COMMERCIAL");
    row.setPriceMonth("2026-10");
    row.setRuleSourceMonth("2026-09");
    row.setRuleRevision(1);
    row.setRowVersion(1L);
    row.setOriginType("CARRY_FORWARD");
    row.setSourceRuleId(2L);
    row.setSourceRuleRevision(1);
    row.setImportBatchNo("IMPORT");
    rules.write(
        new ScrapPriceRule(
            "scrap",
            "废料",
            "",
            "KG",
            supplierCode,
            supplierName,
            fixed == null ? "LINKED" : "FIXED",
            fixed == null ? null : new BigDecimal(fixed),
            fixed == null ? "[Cu]*0.982/1.13" : null,
            fixed == null ? "[Cu]*0.982/1.13" : null,
            fixed == null ? Map.of("Cu", "AVG|1#CU") : Map.of()),
        row);
    FactorPriceSnapshot financial =
        copper == null
            ? null
            : new FactorPriceSnapshot(
                new FactorPriceScope(
                    "COMMERCIAL", "210", "2026-10", "DEFAULT", FactorPriceScope.Condition.GENERAL),
                3L,
                1,
                "finance.xlsx",
                "hash",
                "content",
                Map.of(
                    "AVG|1#CU",
                    new FactorPriceSnapshot.FactorValue(
                        1L, 2L, "铜", "Cu", new BigDecimal(copper), "CNY/KG", true, List.of())),
                List.of("DEFAULT"),
                "test",
                null,
                null);
    var input = calculator.prepare(row, financial);
    row.setCalculationJson(input.evidenceJson());
    row.setInputFingerprint(input.fingerprint());
    row.setUnitPrice(calculator.calculate(input));
    row.setCalcStatus("READY");
    return row;
  }

  protected BigDecimal expected(String cu) {
    return new BigDecimal(cu)
        .multiply(new BigDecimal("0.982"))
        .divide(new BigDecimal("1.13"), 18, RoundingMode.HALF_UP);
  }
}
