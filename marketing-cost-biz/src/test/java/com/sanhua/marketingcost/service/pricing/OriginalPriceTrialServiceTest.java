package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.CompiledFormula;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OriginalPriceTrialServiceTest {
  private final PriceLinkedItemMapper linked = mock(PriceLinkedItemMapper.class);
  private final PriceRangeFactorRuleMapper ranges = mock(PriceRangeFactorRuleMapper.class);
  private final OriginalLinkedRuleSnapshot linkedSnapshots =
      new OriginalLinkedRuleSnapshot(
          new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
  private final OriginalRangeRuleSnapshot rangeSnapshots =
      new OriginalRangeRuleSnapshot(
          new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
  private final FinanceFactorPriceReader finance = mock(FinanceFactorPriceReader.class);
  private final com.sanhua.marketingcost.mapper.FactorUploadBatchMapper batches =
      mock(com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class);
  private final FinanceEffectivePriceService effectivePrices =
      mock(FinanceEffectivePriceService.class);
  private final OriginalLinkedRuleCalculator linkedCalculator =
      mock(
          OriginalLinkedRuleCalculator.class,
          withSettings()
              .mockMaker(org.mockito.MockMakers.INLINE)
              .defaultAnswer(CALLS_REAL_METHODS));
  private final OriginalRangeRuleCalculator rangeCalculator =
      mock(
          OriginalRangeRuleCalculator.class,
          withSettings()
              .mockMaker(org.mockito.MockMakers.INLINE)
              .defaultAnswer(CALLS_REAL_METHODS));
  private final OriginalPriceTrialService service =
      new OriginalPriceTrialService(
          linked,
          ranges,
          rangeSnapshots,
          linkedCalculator,
          rangeCalculator,
          finance,
          batches,
          effectivePrices);
  private final PriceFactorContext context = PriceFactorContextTest.financial();
  private PriceLinkedItem item;

  @BeforeEach
  void fixture() {
    var auth = new UsernamePasswordAuthenticationToken("finance", "unused", List.of());
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    item = new PriceLinkedItem();
    item.setId(7L);
    item.setSourceKind("PUBLIC");
    item.setRuleStatus("PUBLISHED");
    item.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);
    item.setBusinessUnitType("COMMERCIAL");
    item.setPricingMonth("2026-10");
    item.setSourceUploadBatchId(1L);
    var batch = new com.sanhua.marketingcost.entity.FactorUploadBatch();
    batch.setFileName("original.xlsx");
    when(batches.selectById(1L)).thenReturn(batch);
    when(linked.selectById(7L)).thenReturn(item);
    String expression = "ROUND([cu]*0.1+[pcu],4)/1.13";
    var factors =
        Map.of(
            "cu",
                new OriginalLinkedWorkbookParser.FactorInput(
                    "AVG|1#CU",
                    new Address("因素", "E2"),
                    FactorPriceUnit.CNY_KG,
                    "公斤",
                    "AVG|1#CU",
                    "SAME_CANONICAL_FACTOR"),
            "pcu",
                new OriginalLinkedWorkbookParser.FactorInput(
                    "AVG|PCU",
                    new Address("因素", "E3"),
                    FactorPriceUnit.CNY_KG,
                    "公斤",
                    "AVG|PCU",
                    "SAME_CANONICAL_FACTOR"));
    var rule =
        new OriginalLinkedWorkbookParser.Rule(
            identity(),
            "测试料",
            "ImportData",
            2,
            "O2",
            expression,
            "铜价 + 加工费",
            "0.0000",
            false,
            new CompiledFormula(expression, List.of(), Map.of()),
            factors,
            BigDecimal.ONE);
    item.setOrgCode("210");
    item.setMaterialCode("MAT-01");
    item.setSupplierCode("SUP");
    item.setSupplierName("供应商");
    item.setSpecModel("SPEC");
    item.setUnit("KG");
    item.setRuleIdentityKey(identity().identityKey());
    item.setFormulaExpr(expression);
    item.setTaxIncluded(0);
    item.setSourceInputSnapshotJson(linkedSnapshots.write(List.of(rule), "input"));
    when(finance.readDefault(any(), eq("MAT-01"))).thenReturn(context);
    item.setRuleRevision(1);
    var effective = new com.sanhua.marketingcost.entity.FinanceEffectivePrice();
    when(effectivePrices.require(eq(item), any())).thenReturn(effective);
    when(effectivePrices.calculation(effective))
        .thenReturn(
            new FinanceEffectivePriceService.Calculation(
                context, new OriginalLinkedRuleCalculator().calculate(rule, context), null, rule));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private MaterialPriceRuleKey identity() {
    return new MaterialPriceRuleKey("COMMERCIAL", "210", "MAT-01", "SUP", "供应商", "SPEC", "KG");
  }

  private OriginalPriceTrialService.Request request(String cu) {
    return new OriginalPriceTrialService.Request("2026-11", cu == null ? null : new BigDecimal(cu));
  }

  @ParameterizedTest
  @CsvSource({"90000,124.979", "100000,125.979", "80000,123.979"})
  void linkedTrialUsesFormalFormulaAndOnlyOverridesCustomerCopper(String cu, String numerator) {
    var trial = service.linked(7L, request(cu));
    BigDecimal p0 =
        new BigDecimal("124.979").divide(new BigDecimal("1.13"), MathContext.DECIMAL128);
    BigDecimal p1 =
        new BigDecimal(numerator).divide(new BigDecimal("1.13"), MathContext.DECIMAL128);
    assertThat(trial.summary().financePrice()).isEqualByComparingTo(p0);
    assertThat(trial.summary().customerPrice()).isEqualByComparingTo(p1);
    assertThat(trial.summary().difference()).isEqualByComparingTo(p1.subtract(p0));
    assertThat(trial.summary().financeCopperPerTon()).isEqualByComparingTo("90000");
    assertThat(trial.summary().financialSourceMonth()).isEqualTo("2026-10");
    assertThat(trial.summary().pricingMonth()).isEqualTo("2026-11");
    assertThat(trial.finance().inputs().get("pcu")).isEqualByComparingTo("115.979");
    assertThat(trial.customer().inputs().get("pcu")).isEqualByComparingTo("115.979");
    assertThat(context.financial().required(PriceFactorContext.COPPER).price())
        .isEqualByComparingTo("90");
    verify(linked).selectById(7L);
    verifyNoMoreInteractions(linked);
    verifyNoInteractions(ranges);
    verify(finance).readDefault(any(), eq("MAT-01"));
    verifyNoMoreInteractions(finance);
  }

  @Test
  void blankCustomerShowsFinancialPriceAndZeroDifference() {
    assertThat(service.linked(7L, request(null)).summary().difference()).isZero();
    assertThat(service.linked(7L, request("90000")).summary().difference()).isZero();
    verifyNoInteractions(linkedCalculator, rangeCalculator);
  }

  @Test
  void missingFutureAndUnauthorizedRulesFailExplicitly() {
    assertThatThrownBy(() -> service.linked(8L, request(null))).hasMessageContaining("不存在");
    assertThatThrownBy(
            () -> service.linked(7L, new OriginalPriceTrialService.Request("2026-09", null)))
        .hasMessageContaining("早于原稿");
    assertThatThrownBy(() -> service.linked(7L, request("0"))).hasMessageContaining("大于0");
    item.setBusinessUnitType("PLATE");
    assertThatThrownBy(() -> service.linked(7L, request(null))).hasMessageContaining("其他业务单元");
    item.setBusinessUnitType("COMMERCIAL");
    item.setSourceKind("TECH_SUPPLEMENTAL");
    assertThatThrownBy(() -> service.linked(7L, request(null))).hasMessageContaining("不存在");
  }

  @ParameterizedTest
  @CsvSource({"94000,10", "100000,30", "110000,50"})
  void rangeTrialUsesFormalBandSelectionAndVerifiedExtrapolation(String copper, String price) {
    var row = new PriceRangeFactorRule();
    row.setId(8L);
    row.setRuleStatus("PUBLISHED");
    row.setSourceUploadBatchId(1L);
    row.setBusinessUnitType("COMMERCIAL");
    row.setPricingMonth("2026-10");
    when(ranges.selectById(8L)).thenReturn(row);
    var bands = new java.util.ArrayList<OriginalRangeWorkbookParser.Band>();
    for (int n = 0; n < 4; n++) {
      var bounds =
          new OriginalRangeWorkbookParser.Bounds(
              BigDecimal.valueOf(90000 + n * 5000),
              BigDecimal.valueOf(94999 + n * 5000),
              true,
              true);
      var cell = new Address("矩阵", "D" + (n + 1));
      var source =
          new OriginalRangeWorkbookParser.CellSource(
              cell, String.valueOf(10 + n * 10), "NUMERIC", null, "0");
      bands.add(
          new OriginalRangeWorkbookParser.Band(
              bounds, source, cell, source, null, BigDecimal.valueOf(10 + n * 10), null));
    }
    var rule =
        new OriginalRangeWorkbookParser.Rule(
            identity(),
            "测试料",
            "ImportData",
            2,
            null,
            false,
            "AVG|1#CU",
            FactorPriceUnit.CNY_TON,
            "矩阵",
            4,
            "SPEC",
            "供应商",
            "匹配",
            bands,
            List.of());
    row.setOrgCode("210");
    row.setMaterialCode("MAT-01");
    row.setSupplierCode("SUP");
    row.setSupplierName("供应商");
    row.setSpecModel("SPEC");
    row.setPriceUnit("KG");
    row.setRuleIdentityKey(identity().identityKey());
    row.setFactorCode("AVG|1#CU");
    row.setFactorUnit(FactorPriceUnit.CNY_TON.name());
    row.setSourceInputSnapshotJson(rangeSnapshots.write(List.of(rule), "input"));
    var effective = new com.sanhua.marketingcost.entity.FinanceEffectivePrice();
    when(effectivePrices.require(eq(row), any())).thenReturn(effective);
    when(effectivePrices.calculation(effective))
        .thenReturn(
            new FinanceEffectivePriceService.Calculation(
                context, null, new OriginalRangeRuleCalculator().calculate(rule, context), null));
    var result = service.range(8L, request(copper));
    assertThat(result.summary().financePrice()).isEqualByComparingTo("10");
    assertThat(result.summary().customerPrice()).isEqualByComparingTo(price);
    assertThat(result.summary().difference())
        .isEqualByComparingTo(new BigDecimal(price).subtract(BigDecimal.TEN));
    assertThat(result.customer().factorValue()).isEqualByComparingTo(copper);
    assertThat(result.context().customerCopperPricePerTon()).isNull();
    assertThatThrownBy(() -> service.range(8L, request("89999"))).hasMessageContaining("缺少行情档位");
    verify(ranges, times(2)).selectById(8L);
    verifyNoMoreInteractions(ranges);
    verifyNoInteractions(linked);
  }
}
