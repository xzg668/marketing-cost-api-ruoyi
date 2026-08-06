package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.converter;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.row;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-08 类型2税口径规范化")
class PriceLinkedType2TaxNormalizationTest {

  private final PriceLinkedType2TaxNormalizerImpl normalizer =
      new PriceLinkedType2TaxNormalizerImpl();

  @Test
  @DisplayName("FALSE 0 否均解析为不含税并要求计算阶段除税")
  void parsesFalseAliases() {
    PriceLinkedType2FormulaConversionResult conversion = conversion("G6");

    for (String raw : List.of("FALSE", "0", "否")) {
      PriceLinkedType2TaxNormalizationResult result =
          normalizer.normalize(raw, conversion);

      assertThat(result.isSuccess()).as(raw).isTrue();
      assertThat(result.getOriginalTaxIncluded()).as(raw).isZero();
      assertThat(result.getNormalizedTaxIncluded()).as(raw).isZero();
      assertThat(result.isTaxAdjustmentRequired()).as(raw).isTrue();
    }
  }

  @Test
  @DisplayName("TRUE 1 是均解析为含税且不额外除税")
  void parsesTrueAliases() {
    PriceLinkedType2FormulaConversionResult conversion = conversion("G6");

    for (String raw : List.of("TRUE", "1", "是")) {
      PriceLinkedType2TaxNormalizationResult result =
          normalizer.normalize(raw, conversion);

      assertThat(result.isSuccess()).as(raw).isTrue();
      assertThat(result.getOriginalTaxIncluded()).as(raw).isOne();
      assertThat(result.getNormalizedTaxIncluded()).as(raw).isOne();
      assertThat(result.isTaxAdjustmentRequired()).as(raw).isFalse();
    }
  }

  @Test
  @DisplayName("末尾已有除税因子且 FALSE 时导入移除并仍只除一次")
  void keepsFalseWhenFormulaVatDivisorWasStripped() {
    PriceLinkedType2TaxNormalizationResult result =
        normalizer.normalize("FALSE", conversion("G6/1.13"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.isFinalVatDivisorStripped()).isTrue();
    assertThat(result.getNormalizedTaxIncluded()).isZero();
    assertThat(result.isTaxAdjustmentRequired()).isTrue();
    assertThat(result.getWarnings()).isEmpty();
  }

  @Test
  @DisplayName("末尾已有除税因子且 TRUE 时纠正为 FALSE 并给出提示")
  void correctsTrueWhenFormulaVatDivisorWasStripped() {
    PriceLinkedType2TaxNormalizationResult result =
        normalizer.normalize("TRUE", conversion("G6/1.13"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getOriginalTaxIncluded()).isOne();
    assertThat(result.getNormalizedTaxIncluded()).isZero();
    assertThat(result.isTaxAdjustmentRequired()).isTrue();
    assertThat(result.getWarnings()).extracting("code")
        .containsExactly("TAX_INCLUDED_CORRECTED_TO_EXCLUDED");
  }

  @Test
  @DisplayName("空值沿用旧系统默认含税规则并留下提示")
  void defaultsBlankToTaxIncluded() {
    PriceLinkedType2TaxNormalizationResult result =
        normalizer.normalize(" ", conversion("G6"));

    assertThat(result.getNormalizedTaxIncluded()).isOne();
    assertThat(result.getWarnings()).extracting("code")
        .containsExactly("TAX_INCLUDED_DEFAULTED");
  }

  @Test
  @DisplayName("无法识别的税标记明确失败")
  void rejectsUnknownTaxFlag() {
    PriceLinkedType2TaxNormalizationResult result =
        normalizer.normalize("含税价", conversion("G6"));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrors()).extracting("code")
        .containsExactly("TAX_INCLUDED_INVALID");
  }

  private PriceLinkedType2FormulaConversionResult conversion(String formula) {
    return converter().convert(
        row(6, formula, value("G6", "现含税公式结果", "113")),
        List.of());
  }
}
