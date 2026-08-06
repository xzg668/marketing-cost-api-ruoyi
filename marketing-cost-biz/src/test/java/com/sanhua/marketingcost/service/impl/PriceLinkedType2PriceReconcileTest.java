package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.converter;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.factor;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.merged;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.rowWithPrices;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2PriceReconcileResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistry;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-08 类型2价格与 Excel 对账")
class PriceLinkedType2PriceReconcileTest {

  private static final Path TYPE2_SAMPLE = Path.of(
      "/Users/xiexicheng/Desktop/price/采购价表二次开发导入模板-股份251115联动价格导入类型2.xls");
  private final PriceLinkedType2TaxNormalizerImpl taxNormalizer =
      new PriceLinkedType2TaxNormalizerImpl();

  @Test
  @DisplayName("FALSE 公式结果按动态 vat_rate 只除一次")
  void falseDividesByDynamicVatRateOnce() {
    Fixture fixture = fixture("FALSE", "113", "100", "113");
    PriceLinkedType2PriceReconcileResult result =
        reconciler("0.13").reconcile(
            fixture.mergedRow(),
            fixture.conversion(),
            fixture.taxNormalization(),
            null);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getFormulaResult()).isEqualByComparingTo("113");
    assertThat(result.getFinalPrice()).isEqualByComparingTo("100");
    assertThat(result.getVatRate()).isEqualByComparingTo("0.13");
    assertThat(result.getNormalizedTaxIncluded()).isZero();
  }

  @Test
  @DisplayName("TRUE 公式结果保持含税且不额外除税")
  void trueKeepsTaxIncludedFormulaResult() {
    Fixture fixture = fixture("TRUE", "113", null, "113");
    PriceLinkedType2PriceReconcileResult result =
        reconciler(null).reconcile(
            fixture.mergedRow(),
            fixture.conversion(),
            fixture.taxNormalization(),
            null);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getFinalPrice()).isEqualByComparingTo("113");
    assertThat(result.getVatRate()).isNull();
    assertThat(result.getNormalizedTaxIncluded()).isOne();
  }

  @Test
  @DisplayName("公式末尾已带除税因子且 FALSE 时移除后由计算阶段只除一次")
  void strippedVatDivisorWithFalseStillDividesOnlyOnce() {
    Fixture fixture = fixture("FALSE", "113", "100", "113/1.13");
    PriceLinkedType2PriceReconcileResult result =
        reconciler("0.13").reconcile(
            fixture.mergedRow(),
            fixture.conversion(),
            fixture.taxNormalization(),
            null);

    assertThat(fixture.conversion().isFinalVatDivisorStripped()).isTrue();
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getFormulaResult()).isEqualByComparingTo("113");
    assertThat(result.getFinalPrice()).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("公式末尾已带除税因子且 TRUE 时纠正为 FALSE 后只除一次")
  void strippedVatDivisorWithTrueIsCorrectedAndDividesOnlyOnce() {
    Fixture fixture = fixture("TRUE", "113", "100", "113/1.13");
    PriceLinkedType2PriceReconcileResult result =
        reconciler("0.13").reconcile(
            fixture.mergedRow(),
            fixture.conversion(),
            fixture.taxNormalization(),
            null);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getNormalizedTaxIncluded()).isZero();
    assertThat(result.getFinalPrice()).isEqualByComparingTo("100");
    assertThat(result.getWarnings()).extracting("code")
        .contains("TAX_INCLUDED_CORRECTED_TO_EXCLUDED");
  }

  @Test
  @DisplayName("FALSE 缺少 vat_rate 时明确失败")
  void failsWhenVatRateIsMissingForFalse() {
    Fixture fixture = fixture("FALSE", "113", "100", "113");
    PriceLinkedType2PriceReconcileResult result =
        reconciler(null).reconcile(
            fixture.mergedRow(),
            fixture.conversion(),
            fixture.taxNormalization(),
            null);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getFinalPrice()).isNull();
    assertThat(result.getErrors()).extracting("code")
        .contains("VAT_RATE_MISSING");
  }

  @Test
  @DisplayName("对账差异等于允许门限时通过")
  void passesWhenDifferenceEqualsTolerance() {
    Fixture fixture = fixture("TRUE", "9.9999", null, "10");
    PriceLinkedType2PriceReconcileResult result =
        reconciler(null).reconcile(
            fixture.mergedRow(),
            fixture.conversion(),
            fixture.taxNormalization(),
            new BigDecimal("0.0001"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getTaxIncludedComparison().absoluteDifference())
        .isEqualByComparingTo("0.0001");
  }

  @Test
  @DisplayName("对账差异超过允许门限时阻断")
  void blocksWhenDifferenceExceedsTolerance() {
    Fixture fixture = fixture("TRUE", "9.99989", null, "10");
    PriceLinkedType2PriceReconcileResult result =
        reconciler(null).reconcile(
            fixture.mergedRow(),
            fixture.conversion(),
            fixture.taxNormalization(),
            new BigDecimal("0.0001"));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrors()).extracting("code")
        .containsExactly("TAX_INCLUDED_PRICE_MISMATCH");
  }

  @Test
  @DisplayName("vat_rate 从统一变量注册表按月份动态读取")
  void resolvesVatRateFromVariableRegistry() {
    Fixture fixture = fixture("FALSE", "113", "100", "113");
    AtomicReference<String> requestedCode = new AtomicReference<>();
    AtomicReference<VariableContext> requestedContext = new AtomicReference<>();
    FactorVariableRegistry registry = (code, context) -> {
      requestedCode.set(code);
      requestedContext.set(context);
      return Optional.of(new BigDecimal("0.13"));
    };
    PriceLinkedType2PriceReconcilerImpl reconciler =
        new PriceLinkedType2PriceReconcilerImpl(
            new PriceLinkedType2VatRateResolverImpl(registry));

    PriceLinkedType2PriceReconcileResult result = reconciler.reconcile(
        fixture.mergedRow(),
        fixture.conversion(),
        fixture.taxNormalization(),
        null);

    assertThat(result.isSuccess()).isTrue();
    assertThat(requestedCode.get()).isEqualTo("vat_rate");
    assertThat(requestedContext.get().getPricingMonth()).isEqualTo("2026-07");
    assertThat(requestedContext.get().isMonthlyReprice()).isTrue();
  }

  @Test
  @DisplayName("真实 Excel 50 行含税和不含税价格全部通过门限")
  void reconcilesAllConvertibleRealExcelRows() throws Exception {
    PriceLinkedType2WorkbookParseResult workbook = parseRealWorkbook();
    List<PriceLinkedType2FormulaFactorBinding> bindings =
        realFactorBindings(workbook);
    PriceLinkedType2PriceReconcilerImpl reconciler = reconciler("0.13");

    List<PriceLinkedType2PriceReconcileResult> results =
        workbook.getProductRows().stream()
            .map(productRow -> realReconcile(productRow, bindings, reconciler))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .toList();

    assertThat(results).hasSize(50);
    assertThat(results).allSatisfy(result -> {
      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getTaxIncludedComparison().passed()).isTrue();
      assertThat(result.getTaxExcludedComparison().passed()).isTrue();
    });
  }

  private Optional<PriceLinkedType2PriceReconcileResult> realReconcile(
      PriceLinkedType2ProductRow productRow,
      List<PriceLinkedType2FormulaFactorBinding> bindings,
      PriceLinkedType2PriceReconcilerImpl reconciler) {
    PriceLinkedType2FormulaConversionResult conversion =
        converter().convert(productRow, bindings);
    if (!conversion.isSuccess()) {
      return Optional.empty();
    }
    PriceLinkedType2MergedRow mergedRow = merged(productRow, "FALSE");
    PriceLinkedType2TaxNormalizationResult taxNormalization =
        taxNormalizer.normalize("FALSE", conversion);
    return Optional.of(reconciler.reconcile(
        mergedRow, conversion, taxNormalization, new BigDecimal("0.0001")));
  }

  private Fixture fixture(
      String taxIncludedText,
      String excelTaxIncludedPrice,
      String excelTaxExcludedPrice,
      String formula) {
    PriceLinkedType2ProductRow productRow = rowWithPrices(
        6,
        formula,
        excelTaxIncludedPrice,
        excelTaxExcludedPrice,
        value("G6", "公式结果", "10"));
    PriceLinkedType2FormulaConversionResult conversion =
        converter().convert(productRow, List.of());
    PriceLinkedType2MergedRow mergedRow = merged(productRow, taxIncludedText);
    PriceLinkedType2TaxNormalizationResult taxNormalization =
        taxNormalizer.normalize(taxIncludedText, conversion);
    return new Fixture(mergedRow, conversion, taxNormalization);
  }

  private PriceLinkedType2PriceReconcilerImpl reconciler(String vatRate) {
    return new PriceLinkedType2PriceReconcilerImpl(
        row -> vatRate == null
            ? Optional.empty()
            : Optional.of(new BigDecimal(vatRate)));
  }

  private PriceLinkedType2WorkbookParseResult parseRealWorkbook() throws Exception {
    assertThat(Files.exists(TYPE2_SAMPLE)).as("真实类型2样例存在").isTrue();
    byte[] bytes = Files.readAllBytes(TYPE2_SAMPLE);
    return new PriceLinkedType2WorkbookParserImpl(
        new PriceLinkedWorkbookTypeDetectorImpl()).parse(
            new ByteArrayInputStream(bytes), TYPE2_SAMPLE.getFileName().toString());
  }

  private List<PriceLinkedType2FormulaFactorBinding> realFactorBindings(
      PriceLinkedType2WorkbookParseResult workbook) {
    return workbook.getFactorRows().stream()
        .map(this::realFactorBinding)
        .toList();
  }

  private PriceLinkedType2FormulaFactorBinding realFactorBinding(
      PriceLinkedType2FactorRow factorRow) {
    long identityId = "1#Cu".equalsIgnoreCase(factorRow.getShortName()) ? 191L : 192L;
    return factor(
        factorRow.getSourceSheetName(),
        factorRow.getPriceCellRef(),
        factorRow.getShortName(),
        identityId,
        factorRow.getPrice().toPlainString());
  }

  private record Fixture(
      PriceLinkedType2MergedRow mergedRow,
      PriceLinkedType2FormulaConversionResult conversion,
      PriceLinkedType2TaxNormalizationResult taxNormalization) {
  }
}
