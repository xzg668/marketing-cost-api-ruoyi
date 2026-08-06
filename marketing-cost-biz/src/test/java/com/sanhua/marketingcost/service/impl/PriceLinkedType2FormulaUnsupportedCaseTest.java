package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.converter;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.factor;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.row;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-07 类型2公式不支持场景阻断")
class PriceLinkedType2FormulaUnsupportedCaseTest {

  @Test
  @DisplayName("外部工作簿引用必须阻断")
  void blocksExternalWorkbookReference() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "'[book.xls]因素'!$E$2+G6",
            value("[book.xls]因素", "E2", "1#Cu", "90", null, null),
            value("G6", "毛重", "1")),
        List.of(factor("[book.xls]因素", "E2", "1#Cu", 191L, "90")));

    assertError(result, "EXTERNAL_WORKBOOK_REFERENCE");
  }

  @Test
  @DisplayName("单元格区域引用必须阻断")
  void blocksCellRange() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "G6:H6", value("G6", "毛重", "1"), value("H6", "净重", "2")),
        List.of());

    assertError(result, "CELL_RANGE_UNSUPPORTED");
  }

  @Test
  @DisplayName("未知 Excel 函数必须阻断")
  void blocksUnsupportedFunction() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "SUM(G6,H6)",
            value("G6", "毛重", "1"),
            value("H6", "净重", "2")),
        List.of());

    assertError(result, "FUNCTION_UNSUPPORTED");
  }

  @Test
  @DisplayName("空公式必须阻断")
  void blocksEmptyFormula() {
    assertError(converter().convert(row(6, "  "), List.of()), "EMPTY_FORMULA");
  }

  @Test
  @DisplayName("缺少产品公式行必须阻断")
  void blocksMissingFormulaRow() {
    assertError(converter().convert(null, List.of()), "FORMULA_ROW_MISSING");
  }

  @Test
  @DisplayName("公式引用缺少来源快照必须阻断")
  void blocksMissingReferenceSnapshot() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "G6"), List.of());

    assertError(result, "REFERENCE_SNAPSHOT_MISSING");
  }

  @Test
  @DisplayName("非空文本引用值不是数字必须阻断")
  void blocksNonNumericReference() {
    PriceLinkedType2CellSnapshot text = new PriceLinkedType2CellSnapshot(
        "Sheet1", "G6", "毛重", "未维护", null, null, "克", "STRING", false);
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "G6", text), List.of());

    assertError(result, "REFERENCE_NOT_NUMERIC");
  }

  @Test
  @DisplayName("因素尚未绑定统一主身份必须阻断")
  void blocksFactorWithoutIdentity() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "E2", value("E2", "1#Cu", "90")),
        List.of(new com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding(
            "Sheet1", "E2", "1#Cu", null, new java.math.BigDecimal("90"))));

    assertError(result, "FACTOR_IDENTITY_MISSING");
  }

  private void assertError(
      PriceLinkedType2FormulaConversionResult result, String errorCode) {
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getConvertedFormula()).isNull();
    assertThat(result.getErrors()).extracting("code").contains(errorCode);
  }
}
