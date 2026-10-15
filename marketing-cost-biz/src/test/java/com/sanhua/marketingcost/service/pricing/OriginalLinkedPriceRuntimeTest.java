package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.CompiledFormula;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class OriginalLinkedPriceRuntimeTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final OriginalLinkedRuleSnapshot snapshots = new OriginalLinkedRuleSnapshot(json);
  private final FinanceFactorPriceReader finance = mock(FinanceFactorPriceReader.class);
  private final OriginalLinkedPriceRuntime runtime =
      new OriginalLinkedPriceRuntime(snapshots, new OriginalLinkedRuleCalculator(), finance, json);

  @Test
  void publicPriceAndUnchangedCustomerUseSavedResultWithoutReadingFinanceOrCalculating() {
    var financial = PriceFactorContextTest.financial();
    var item = item("[cu]+5");
    var rule = snapshots.read(item);
    var calculator =
        mock(
            OriginalLinkedRuleCalculator.class,
            withSettings()
                .mockMaker(org.mockito.MockMakers.INLINE)
                .defaultAnswer(CALLS_REAL_METHODS));
    var savedResult = calculator.calculate(rule, financial);
    var saved = new FinanceEffectivePriceService.Calculation(financial, savedResult, null, rule);
    var effective = new com.sanhua.marketingcost.entity.FinanceEffectivePrice();
    effective.setId(10L);
    effective.setInputFingerprint("saved");
    var runtime = new OriginalLinkedPriceRuntime(snapshots, calculator, finance, json);
    clearInvocations(calculator);
    assertThat(runtime.previewSaved(item, "2026-11", null, null, effective, saved).getResult())
        .isEqualByComparingTo(savedResult.priceExcludingTax());
    var copper = financial.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_TON, true);
    assertThat(runtime.previewSaved(item, "2026-11", copper, null, effective, saved).getResult())
        .isEqualByComparingTo(savedResult.priceExcludingTax());
    verifyNoInteractions(calculator, finance);
    assertThat(
            runtime
                .previewSaved(item, "2026-11", new BigDecimal("100000"), null, effective, saved)
                .getResult())
        .isEqualByComparingTo("105");
    verify(calculator, times(1)).calculate(any(), any());
    verifyNoInteractions(finance);
    // 原稿早期快照变化不能改变已选发布中客户计算所用的绑定规则。
    item.setSourceInputSnapshotJson("{}");
    assertThat(
            runtime
                .previewSaved(item, "2026-11", new BigDecimal("100000"), null, effective, saved)
                .getResult())
        .isEqualByComparingTo("105");
  }

  @Test
  void evidenceReplaysActualPricesAndKeepsCompleteUsedSources() throws Exception {
    var financial = PriceFactorContextTest.financial();
    when(finance.readDefault(any(), any())).thenReturn(financial);
    var result = runtime.preview(item("[cu]+5"), "2026-11", new BigDecimal("100000"));
    assertThat(result.getError()).isNull();
    assertThat(result.getResult()).isEqualByComparingTo("105");
    var evidence = json.readTree(result.getInputSnapshotJson());
    var context = json.treeToValue(evidence.path("context"), PriceFactorContext.class);
    assertThat(context.financial().factors()).containsOnlyKeys(PriceFactorContext.COPPER);
    assertThat(context.financial().required(PriceFactorContext.COPPER))
        .isEqualTo(financial.financial().required(PriceFactorContext.COPPER));
    assertThat(context.financial().contentHash()).isEqualTo(financial.financial().contentHash());
    assertThat(
            context
                .requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true)
                .add(new BigDecimal("5")))
        .isEqualByComparingTo(result.getResult());
    assertThat(financial.financial().factors()).hasSize(6);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void savedBoardConditionKeepsCustomerStainlessOnBothCopperSides(boolean tender) {
    var financial =
        BoardPriceContextTest.financial(tender)
            .withBoardInputs(new BoardPriceInputs(null, null, tender));
    var key =
        new MaterialPriceRuleKey("COMMERCIAL", "220", "BOARD-MATERIAL", "S1", "S1", "SPEC", "只");
    var rule =
        new OriginalLinkedWorkbookParser.Rule(
            key,
            "板换",
            "ImportData",
            2,
            "H2",
            "S+Cu",
            "S+Cu",
            "0.00",
            false,
            new CompiledFormula(
                "[s]+[cu]", List.of(), Map.of("s", BigDecimal.TEN, "cu", new BigDecimal("90"))),
            Map.of(
                "s",
                new OriginalLinkedWorkbookParser.FactorInput(
                    "AVG|304/2B/1219",
                    new Address("因素", "E2"),
                    FactorPriceUnit.CNY_KG,
                    "元/公斤",
                    "AVG|304/2B/1219",
                    "SAME_CANONICAL_FACTOR"),
                "cu",
                new OriginalLinkedWorkbookParser.FactorInput(
                    PriceFactorContext.COPPER,
                    new Address("因素", "E3"),
                    FactorPriceUnit.CNY_KG,
                    "元/公斤",
                    PriceFactorContext.COPPER,
                    "SAME_CANONICAL_FACTOR")),
            BigDecimal.ZERO);
    var calculator =
        mock(
            OriginalLinkedRuleCalculator.class,
            withSettings()
                .mockMaker(org.mockito.MockMakers.INLINE)
                .defaultAnswer(CALLS_REAL_METHODS));
    var result = calculator.calculate(rule, financial);
    var saved = new FinanceEffectivePriceService.Calculation(financial, result, null, rule);
    var effective = new com.sanhua.marketingcost.entity.FinanceEffectivePrice();
    effective.setId(20L);
    var item = item("[cu]+5");
    item.setOrgCode("220");
    item.setMaterialCode("BOARD-MATERIAL");
    var runtime = new OriginalLinkedPriceRuntime(snapshots, calculator, finance, json);
    clearInvocations(calculator);
    assertThat(runtime.previewSaved(item, "2026-10", null, null, effective, saved).getResult())
        .isEqualByComparingTo(tender ? "105" : "110");
    verifyNoInteractions(calculator, finance);
    var oa = new com.sanhua.marketingcost.entity.OaForm();
    oa.setBoardTender(tender);
    oa.setSus304Price(new BigDecimal("25000"));
    assertThat(runtime.previewSaved(item, "2026-10", null, oa, effective, saved).getResult())
        .isEqualByComparingTo("115");
    assertThat(
            runtime
                .previewSaved(item, "2026-10", new BigDecimal("100000"), oa, effective, saved)
                .getResult())
        .isEqualByComparingTo("125");
    assertThat(saved.linked().priceExcludingTax()).isEqualByComparingTo(tender ? "105" : "110");
    verify(calculator, times(2)).calculate(any(), any());
    verifyNoInteractions(finance);
  }

  private PriceLinkedItem item(String expression) {
    var key = new MaterialPriceRuleKey("COMMERCIAL", "210", "MAT-01", "SUP", "供应商", "SPEC", "只");
    var rule =
        new OriginalLinkedWorkbookParser.Rule(
            key,
            "原稿部件",
            "ImportData",
            2,
            "H2",
            expression,
            "铜价 + 加工费",
            "0.0000",
            false,
            new CompiledFormula(expression, List.of(), Map.of("cu", new BigDecimal("109.114"))),
            Map.of(
                "cu",
                new OriginalLinkedWorkbookParser.FactorInput(
                    PriceFactorContext.COPPER,
                    new Address("因素", "E2"),
                    FactorPriceUnit.CNY_KG,
                    "公斤",
                    PriceFactorContext.COPPER,
                    "SAME_CANONICAL_FACTOR")),
            new BigDecimal("114.114"));
    var item = new PriceLinkedItem();
    item.setId(1L);
    item.setRuleRevision(1);
    item.setRuleIdentityKey(key.identityKey());
    item.setBusinessUnitType(key.businessUnitType());
    item.setOrgCode(key.orgCode());
    item.setMaterialCode(key.materialCode());
    item.setSupplierCode(key.supplierCode());
    item.setSupplierName(key.supplierName());
    item.setSpecModel(key.specification());
    item.setUnit(key.unit());
    item.setPricingMonth("2026-10");
    item.setTaxIncluded(0);
    item.setFormulaExpr(expression);
    item.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);
    item.setSourceInputSnapshotJson(snapshots.write(List.of(rule), "original-version"));
    return item;
  }
}
