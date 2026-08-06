package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.factor;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.blank;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.row;
import static com.sanhua.marketingcost.service.impl.PriceLinkedType2FormulaTestSupport.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaReferenceClassification;
import com.sanhua.marketingcost.enums.PriceLinkedType2FormulaReferenceType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-07 类型2公式引用分类")
class PriceLinkedType2FormulaReferenceClassifierTest {

  private final PriceLinkedType2FormulaReferenceClassifierImpl classifier =
      new PriceLinkedType2FormulaReferenceClassifierImpl();

  @Test
  @DisplayName("绝对与相对写法归并为同一个动态因素引用")
  void deduplicatesAbsoluteAndRelativeFactorReferences() {
    PriceLinkedType2FormulaReferenceClassification result = classifier.classify(
        row(6, "$E$2+E2", value("E2", "1#Cu", "90")),
        List.of(factor("E2", "1#Cu", 191L, "90")));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getReferences()).singleElement().satisfies(reference -> {
      assertThat(reference.referenceType())
          .isEqualTo(PriceLinkedType2FormulaReferenceType.FACTOR_DYNAMIC);
      assertThat(reference.replacement()).isEqualTo("[factor_identity_191]");
    });
  }

  @Test
  @DisplayName("已识别因素允许从指定因素 Sheet 跨表引用")
  void allowsRecognizedFactorAcrossSheet() {
    PriceLinkedType2FormulaReferenceClassification result = classifier.classify(
        row(6, "'影响因素'!$E$2+G6",
            value("影响因素", "E2", "1#Cu", "90", null, "公斤"),
            value("G6", "毛重", "1")),
        List.of(factor("影响因素", "E2", "1#Cu", 191L, "90")));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getReferences()).hasSize(2);
  }

  @Test
  @DisplayName("当前产品行数字被识别为输入快照")
  void classifiesCurrentRowNumericInput() {
    PriceLinkedType2FormulaReferenceClassification result = classifier.classify(
        row(6, "G6+H6",
            value("G6", "毛重", "1"),
            value("H6", "加工费", "2")),
        List.of());

    assertThat(result.getReferences())
        .extracting(reference -> reference.referenceType())
        .containsOnly(PriceLinkedType2FormulaReferenceType.ROW_NUMERIC);
  }

  @Test
  @DisplayName("当前产品行普通空白单元格按Excel算术语义替换为0")
  void defaultsCurrentRowBlankInputToZero() {
    PriceLinkedType2FormulaReferenceClassification result = classifier.classify(
        row(6, "I6+J6+L6",
            blank("I6", "紫铜毛细管毛重（g)", "g"),
            blank("J6", "紫铜毛细管净重（g)", "g"),
            blank("L6", "毛细管含税加工费", null)),
        List.of());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getReferences()).hasSize(3).allSatisfy(reference -> {
      assertThat(reference.referenceType())
          .isEqualTo(PriceLinkedType2FormulaReferenceType.ROW_NUMERIC);
      assertThat(reference.numericValue()).isZero();
      assertThat(reference.replacement()).isEqualTo("0");
    });
  }

  @Test
  @DisplayName("影响因素单元格为空不能按0处理")
  void blocksBlankFactorPrice() {
    PriceLinkedType2FormulaReferenceClassification result = classifier.classify(
        row(6, "$E$2+G6",
            blank("E2", "1#Cu", "公斤"),
            value("G6", "加工费", "1")),
        List.of(new PriceLinkedType2FormulaFactorBinding(
            "Sheet1", "E2", "1#Cu", 191L, null)));

    assertThat(result.getErrors()).extracting("code")
        .containsExactly("REFERENCE_NOT_NUMERIC");
  }

  @Test
  @DisplayName("引用其他产品行必须阻断")
  void blocksOtherProductRow() {
    PriceLinkedType2FormulaReferenceClassification result = classifier.classify(
        row(6, "G7", value("G7", "其他行毛重", "1")),
        List.of());

    assertThat(result.getErrors()).extracting("code")
        .containsExactly("OTHER_PRODUCT_ROW_REFERENCE");
  }

  @Test
  @DisplayName("无法识别业务含义的跨 Sheet 引用必须阻断")
  void blocksUnknownCrossSheetReference() {
    PriceLinkedType2FormulaReferenceClassification result = classifier.classify(
        row(6, "'其他表'!G6",
            value("其他表", "G6", "未知输入", "1", null, null)),
        List.of());

    assertThat(result.getErrors()).extracting("code")
        .containsExactly("UNKNOWN_CROSS_SHEET_REFERENCE");
  }

  @Test
  @DisplayName("同一因素格绑定多个统一身份必须阻断")
  void blocksConflictingFactorBindings() {
    PriceLinkedType2FormulaFactorBinding first =
        factor("E2", "1#Cu", 191L, "90");
    PriceLinkedType2FormulaFactorBinding second =
        factor("E2", "1#Cu", 999L, "90");
    PriceLinkedType2FormulaReferenceClassification result = classifier.classify(
        row(6, "E2", value("E2", "1#Cu", "90")),
        List.of(first, second));

    assertThat(result.getErrors()).extracting("code")
        .contains("FACTOR_BINDING_CONFLICT");
  }

  @Test
  @DisplayName("公式直接引用自身或输入格回指公式格均判定循环引用")
  void blocksDirectAndIndirectCircularReferences() {
    PriceLinkedType2FormulaReferenceClassification direct = classifier.classify(
        row(6, "R6+G6",
            value("R6", "现含税价", "1"),
            value("G6", "毛重", "1")),
        List.of());
    PriceLinkedType2FormulaReferenceClassification indirect = classifier.classify(
        row(6, "G6",
            value("Sheet1", "G6", "毛重", "1", "R6+1", "克")),
        List.of());

    assertThat(direct.getErrors()).extracting("code").contains("CIRCULAR_REFERENCE");
    assertThat(indirect.getErrors()).extracting("code").containsExactly("CIRCULAR_REFERENCE");
  }
}
