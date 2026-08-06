package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.converter;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.factor;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.row;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-07 类型2联动公式转换")
class PriceLinkedType2FormulaConverterTest {

  @Test
  @DisplayName("Cu 引用动态化而本行重量和加工费固化")
  void convertsCuAndRowValues() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "=G6*$E$2/1000+H6",
            value("G6", "铜毛重", "10"),
            value("E2", "1#Cu", "90"),
            value("H6", "加工费", "1.25")),
        List.of(factor("E2", "1#Cu", 191L, "90")));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getConvertedFormula())
        .isEqualTo("10*[factor_identity_191]/1000+1.25");
  }

  @Test
  @DisplayName("Zn 单因素可以独立转换")
  void convertsZnOnly() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "$E$3*G6",
            value("E3", "1#Zn", "21.68"),
            value("G6", "锌毛重", "2")),
        List.of(factor("E3", "1#Zn", 192L, "21.68")));

    assertThat(result.getConvertedFormula()).isEqualTo("[factor_identity_192]*2");
  }

  @Test
  @DisplayName("Cu Zn 组合、括号和运算顺序保持不变")
  void convertsCuZnAndParentheses() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "G6*($E$2*0.59+$E$3*0.41)-H6",
            value("G6", "毛重", "10"),
            value("E2", "1#Cu", "90"),
            value("E3", "1#Zn", "21.68"),
            value("H6", "净重", "9")),
        List.of(
            factor("E2", "1#Cu", 191L, "90"),
            factor("E3", "1#Zn", 192L, "21.68")));

    assertThat(result.getConvertedFormula()).isEqualTo(
        "10*([factor_identity_191]*0.59+[factor_identity_192]*0.41)-9");
  }

  @Test
  @DisplayName("多组重量加工费和相对绝对引用均按同一产品行快照固化")
  void convertsMultipleWeightsAndFees() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "G6/1000*($E$2+L6)-(G6-H6)/1000*$E$3+Q6",
            value("G6", "铜毛重", "10"),
            value("E2", "1#Cu", "90"),
            value("L6", "铜加工费", "1"),
            value("H6", "铜净重", "9"),
            value("E3", "1#Zn", "21.68"),
            value("Q6", "分配器加工费", "2")),
        List.of(
            factor("E2", "1#Cu", 191L, "90"),
            factor("E3", "1#Zn", 192L, "21.68")));

    assertThat(result.getConvertedFormula()).isEqualTo(
        "10/1000*([factor_identity_191]+1)"
            + "-(10-9)/1000*[factor_identity_192]+2");
    assertThat(result.getConvertedFormula()).contains("/1000");
  }

  @Test
  @DisplayName("外层 ROUND 按系统精度规范移除并记录小数位")
  void stripsOuterRoundAndRecordsScale() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "ROUND(G6*$E$2/1000+H6,4)",
            value("G6", "毛重", "10"),
            value("E2", "1#Cu", "90"),
            value("H6", "加工费", "1")),
        List.of(factor("E2", "1#Cu", 191L, "90")));

    assertThat(result.getConvertedFormula())
        .isEqualTo("(10*[factor_identity_191]/1000+1)");
    assertThat(result.getStrippedRoundScales()).containsExactly(4);
  }

  @Test
  @DisplayName("公式末尾已有除税时只移除一次并留下审计标识")
  void stripsOnlyFinalVatDivisor() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "ROUND(G6*$E$2,4)/1.13",
            value("G6", "毛重", "10"),
            value("E2", "1#Cu", "90")),
        List.of(factor("E2", "1#Cu", 191L, "90")));

    assertThat(result.getConvertedFormula())
        .isEqualTo("(10*[factor_identity_191])");
    assertThat(result.isFinalVatDivisorStripped()).isTrue();
  }

  @Test
  @DisplayName("零值负值和高精度小数能够无损固化")
  void preservesZeroNegativeAndDecimalPrecision() {
    PriceLinkedType2FormulaConversionResult result = converter().convert(
        row(6, "G6+$E$2*H6+Q6",
            value("G6", "零值", "0"),
            value("E2", "1#Cu", "90"),
            value("H6", "负调整", "-1.2500"),
            value("Q6", "高精度费用", "0.000000123456")),
        List.of(factor("E2", "1#Cu", 191L, "90")));

    assertThat(result.getConvertedFormula()).isEqualTo(
        "0+[factor_identity_191]*(-1.25)+0.000000123456");
  }
}
