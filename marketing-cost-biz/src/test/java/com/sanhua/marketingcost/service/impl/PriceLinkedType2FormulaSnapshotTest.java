package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.converter;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.factor;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.row;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-07 类型2公式来源快照")
class PriceLinkedType2FormulaSnapshotTest {

  private static final Path TYPE2_SAMPLE = Path.of(
      "/Users/xiexicheng/Desktop/price/采购价表二次开发导入模板-股份251115联动价格导入类型2.xls");

  @Test
  @DisplayName("结果保留原公式、输入快照、引用明细和因素替换明细")
  void retainsCompleteAuditTrail() {
    PriceLinkedType2CellSnapshot weight = value("G6", "铜毛重", "10");
    PriceLinkedType2CellSnapshot cu = value("E2", "1#Cu", "90");
    String sourceFormula = "ROUND(G6*$E$2/1000,4)";

    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, sourceFormula, weight, cu),
        List.of(factor("E2", "1#Cu", 191L, "90")));

    assertThat(result.getSourceFormula()).isEqualTo(sourceFormula);
    assertThat(result.getInputSnapshots()).containsExactly(weight, cu);
    assertThat(result.getReferences()).hasSize(2);
    assertThat(result.getFactorReplacements()).singleElement()
        .satisfies(reference -> {
          assertThat(reference.factorShortName()).isEqualTo("1#Cu");
          assertThat(reference.factorIdentityId()).isEqualTo(191L);
          assertThat(reference.replacement()).isEqualTo("[factor_identity_191]");
        });
  }

  @Test
  @DisplayName("审计列表为只读快照不可由调用方篡改")
  void exposesImmutableAuditLists() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "G6", value("G6", "毛重", "1")),
        List.of());

    assertThatThrownBy(() -> result.getReferences().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.getInputSnapshots().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("真实 Excel 首行按原始公式转换并保留克到公斤除数")
  void convertsFirstRealExcelFormula() throws Exception {
    PriceLinkedType2WorkbookParseResult workbook = parseRealWorkbook();
    PriceLinkedType2ProductRow first = workbook.getProductRows().getFirst();

    PriceLinkedType2FormulaConversionResult result = converter().convert(
        first, realFactorBindings(workbook));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getSourceFormula()).startsWith("ROUND(");
    assertThat(result.getConvertedFormula())
        .contains("[factor_identity_191]", "[factor_identity_192]", "/1000");
    assertThat(result.getReferences()).hasSize(11);
    assertThat(result.getFactorReplacements()).hasSize(2);
    assertThat(result.getStrippedRoundScales()).containsExactly(4);
  }

  @Test
  @DisplayName("真实 Excel 尾部行普通空白引用按0转换并保留原始空白快照")
  void convertsRealExcelRowWithBlankReferencedInputsToZero() throws Exception {
    PriceLinkedType2WorkbookParseResult workbook = parseRealWorkbook();
    PriceLinkedType2ProductRow last = workbook.getProductRows().getLast();

    PriceLinkedType2FormulaConversionResult result = converter().convert(
        last, realFactorBindings(workbook));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getReferences())
        .filteredOn(reference -> List.of("I55", "J55", "L55")
            .contains(reference.cellRef()))
        .hasSize(3)
        .allSatisfy(reference -> {
          assertThat(reference.numericValue()).isZero();
          assertThat(reference.replacement()).isEqualTo("0");
        });
    assertThat(result.getInputSnapshots())
        .filteredOn(snapshot -> List.of("I55", "J55", "L55")
            .contains(snapshot.getCellRef()))
        .hasSize(3)
        .allSatisfy(snapshot -> {
          assertThat(snapshot.isBlankCell()).isTrue();
          assertThat(snapshot.getNumericValue()).isNull();
        });
  }

  @Test
  @DisplayName("真实 Excel 50 行全部完成公式转换")
  void convertsAllRealExcelRowsWithAuditableSummary() throws Exception {
    PriceLinkedType2WorkbookParseResult workbook = parseRealWorkbook();
    List<PriceLinkedType2FormulaFactorBinding> bindings =
        realFactorBindings(workbook);
    List<PriceLinkedType2FormulaConversionResult> results =
        workbook.getProductRows().stream()
            .map(row -> converter().convert(row, bindings))
            .toList();

    assertThat(results).hasSize(50);
    assertThat(results).filteredOn(PriceLinkedType2FormulaConversionResult::isSuccess)
        .hasSize(50);
    assertThat(results).allSatisfy(result -> {
      assertThat(result.getConvertedFormula()).isNotBlank();
      assertThat(result.getErrors()).isEmpty();
    });
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
    return new PriceLinkedType2FormulaFactorBinding(
        factorRow.getSourceSheetName(),
        factorRow.getPriceCellRef(),
        factorRow.getShortName(),
        identityId,
        factorRow.getPrice());
  }
}
