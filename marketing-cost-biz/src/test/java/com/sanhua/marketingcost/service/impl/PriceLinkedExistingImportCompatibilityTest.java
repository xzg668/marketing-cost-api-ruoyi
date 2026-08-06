package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-13 旧联动价五条基线兼容性")
class PriceLinkedExistingImportCompatibilityTest {

  private static final BigDecimal VAT_DIVISOR = new BigDecimal("1.13");
  private static final Map<Long, BigDecimal> JULY_FACTOR_PRICES = Map.of(
      191L, new BigDecimal("90.000000"),
      192L, new BigDecimal("21.680000"),
      204L, new BigDecimal("53.848000"));

  @Test
  @DisplayName("五条旧料号公式、中文公式、税标记、日期、绑定和预览结果保持基线")
  void fiveLegacyItemsKeepFrozenContractsAndResults() {
    List<LegacyCase> cases = legacyCases();

    assertThat(cases).hasSize(5);
    assertThat(cases).extracting(LegacyCase::id)
        .containsExactly(142L, 147L, 148L, 151L, 157L);
    assertThat(cases).extracting(LegacyCase::supplierCode)
        .containsExactly("S001301", "S001052", "S000495", "S001171", "S001315");
    assertThat(cases.stream().mapToInt(row -> row.bindingFactorIds().size()).sum())
        .as("PLI2-00 冻结的旧变量绑定总数")
        .isEqualTo(9);

    for (LegacyCase baseline : cases) {
      PriceLinkedItem item = toEntity(baseline);

      assertThat(item.getFormulaExpr()).isEqualTo(baseline.formulaExpr());
      assertThat(item.getFormulaExprCn()).isEqualTo(baseline.formulaExprCn());
      assertThat(item.getTaxIncluded()).isZero();
      assertThat(item.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
      assertThat(item.getEffectiveTo()).isNull();
      assertThat(baseline.bindingFactorIds())
          .allMatch(JULY_FACTOR_PRICES::containsKey);
      assertThat(baseline.bindingSource()).isEqualTo("EXCEL_FORMULA");
      assertLegacySourceSnapshotIsEmpty(item);

      BigDecimal formulaResult = ExpressionEvaluator.evaluate(
          item.getFormulaExpr(), evaluationValues(item));
      assertThat(formulaResult).isNotNull();
      BigDecimal preview = formulaResult.divide(VAT_DIVISOR, 12, RoundingMode.HALF_UP);
      assertThat(preview.subtract(baseline.expectedPreview()).abs())
          .as("旧料号 %s / %s 的 2026-07 公式预览", item.getMaterialCode(),
              item.getSupplierCode())
          .isLessThan(new BigDecimal("0.000001"));
      assertThat(preview.setScale(6, RoundingMode.HALF_UP))
          .isEqualByComparingTo(baseline.expectedPreview().setScale(6, RoundingMode.HALF_UP));
    }
  }

  @Test
  @DisplayName("旧月度因素价格仍是191铜90、192锌21.68、204美国柜装黄铜53.848")
  void legacyMonthlyFactorPricesRemainFrozen() {
    assertThat(JULY_FACTOR_PRICES)
        .containsEntry(191L, new BigDecimal("90.000000"))
        .containsEntry(192L, new BigDecimal("21.680000"))
        .containsEntry(204L, new BigDecimal("53.848000"));
  }

  private PriceLinkedItem toEntity(LegacyCase baseline) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setId(baseline.id());
    item.setPricingMonth("2026-07");
    item.setBusinessUnitType("COMMERCIAL");
    item.setMaterialCode(baseline.materialCode());
    item.setSupplierCode(baseline.supplierCode());
    item.setFormulaExpr(baseline.formulaExpr());
    item.setFormulaExprCn(baseline.formulaExprCn());
    item.setBlankWeight(baseline.blankWeightG());
    item.setNetWeight(baseline.netWeightG());
    item.setProcessFee(baseline.processFee());
    item.setTaxIncluded(0);
    item.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    return item;
  }

  private Map<String, BigDecimal> evaluationValues(PriceLinkedItem item) {
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    values.put("factor_identity_191", JULY_FACTOR_PRICES.get(191L));
    values.put("factor_identity_192", JULY_FACTOR_PRICES.get(192L));
    values.put("factor_identity_204", JULY_FACTOR_PRICES.get(204L));
    values.put("process_fee", item.getProcessFee());
    if (item.getBlankWeight() != null) {
      values.put("blank_weight", item.getBlankWeight().movePointLeft(3));
    }
    if (item.getNetWeight() != null) {
      values.put("net_weight", item.getNetWeight().movePointLeft(3));
    }
    return values;
  }

  private void assertLegacySourceSnapshotIsEmpty(PriceLinkedItem item) {
    assertThat(item.getSourceUploadBatchId()).isNull();
    assertThat(item.getSourceSheetName()).isNull();
    assertThat(item.getSourceRowNumber()).isNull();
    assertThat(item.getSourceFormulaCellRef()).isNull();
    assertThat(item.getSourceFormulaExpr()).isNull();
    assertThat(item.getSourceInputSnapshotJson()).isNull();
    assertThat(item.getSourceTaxIncludedPrice()).isNull();
    assertThat(item.getSourceTaxExcludedPrice()).isNull();
  }

  private List<LegacyCase> legacyCases() {
    String brassFormula =
        "([blank_weight]*(([factor_identity_191]*0.15+[factor_identity_192]*0.1"
            + "+[factor_identity_204]*0.75*1.05)*1.02+0.05)"
            + "-([blank_weight]-[net_weight])*(([factor_identity_191]*0.15"
            + "+[factor_identity_192]*0.1+[factor_identity_204]*0.75*1.05)"
            + "*1.02+0.05-0.05)*0.93+[process_fee])";
    String brassFormulaCn =
        "(下料重量*((1#Cu*0.15+1#Zn*0.1+美国柜装黄铜*0.75*1.05)*1.02+0.05)"
            + "-(下料重量-产品净重)*((1#Cu*0.15+1#Zn*0.1"
            + "+美国柜装黄铜*0.75*1.05)*1.02+0.05-0.05)*0.93+加工费)";
    return List.of(
        new LegacyCase(
            142L,
            "301050013",
            "S001301",
            "([factor_identity_191]+[process_fee])",
            "(1#Cu+加工费)",
            null,
            null,
            new BigDecimal("4.350001"),
            List.of(191L),
            "EXCEL_FORMULA",
            new BigDecimal("83.495576106195")),
        new LegacyCase(
            147L,
            "201500340",
            "S001052",
            brassFormula,
            brassFormulaCn,
            new BigDecimal("188.900000"),
            new BigDecimal("78.200000"),
            new BigDecimal("1.039600"),
            List.of(191L, 192L),
            "EXCEL_FORMULA",
            new BigDecimal("5.433817613216")),
        new LegacyCase(
            148L,
            "201500340",
            "S000495",
            brassFormula,
            brassFormulaCn,
            new BigDecimal("188.900000"),
            new BigDecimal("78.200000"),
            new BigDecimal("1.039600"),
            List.of(191L, 192L),
            "EXCEL_FORMULA",
            new BigDecimal("5.433817613216")),
        new LegacyCase(
            151L,
            "201502458",
            "S001171",
            "((([factor_identity_191]*0.15+[factor_identity_192]*0.1"
                + "+[factor_identity_204]*0.75*1.06)*1.02+0.38"
                + "+[process_fee])*[net_weight])",
            "(((1#Cu*0.15+1#Zn*0.1+美国柜装黄铜*0.75*1.06)"
                + "*1.02+0.38+加工费)*产品净重)",
            null,
            new BigDecimal("299.000000"),
            new BigDecimal("3.863248"),
            List.of(191L, 192L),
            "EXCEL_FORMULA",
            new BigDecimal("16.905394167080")),
        new LegacyCase(
            157L,
            "301110045",
            "S001315",
            "([factor_identity_191]*0.59/0.98+[factor_identity_192]*0.41/0.95"
                + "+[process_fee])",
            "(1#Cu*0.59/0.98+1#Zn*0.41/0.95+加工费)",
            null,
            null,
            new BigDecimal("2.280001"),
            List.of(191L, 192L),
            "EXCEL_FORMULA",
            new BigDecimal("58.248058449854")));
  }

  private record LegacyCase(
      Long id,
      String materialCode,
      String supplierCode,
      String formulaExpr,
      String formulaExprCn,
      BigDecimal blankWeightG,
      BigDecimal netWeightG,
      BigDecimal processFee,
      List<Long> bindingFactorIds,
      String bindingSource,
      BigDecimal expectedPreview) {
  }
}
