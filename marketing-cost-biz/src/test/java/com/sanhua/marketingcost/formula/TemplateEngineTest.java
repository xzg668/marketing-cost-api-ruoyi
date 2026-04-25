package com.sanhua.marketingcost.formula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.sanhua.marketingcost.formula.templates.AlloyScrapTemplate;
import com.sanhua.marketingcost.formula.templates.MaterialUnitPlusFeeTemplate;
import com.sanhua.marketingcost.formula.templates.PlanUpliftTemplate;
import com.sanhua.marketingcost.formula.templates.SingleMetalTemplate;
import com.sanhua.marketingcost.formula.templates.WeldAlloyTemplate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模板引擎单元测试 —— 全 5 模板覆盖 + 引擎层错误路径。
 *
 * <p>金标 Excel 端到端验证（如端盖 2.94115）依赖 VariableRegistry 解出 Cu/Zn 等基准价，
 * 该验证下沉到 Task #7 的集成测试；本测试仅校验"模板算法骨架本身正确"。
 */
class TemplateEngineTest {

  /** 默认对比容差，与金标 L2 一致 */
  private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

  private final TemplateEngine engine =
      new TemplateEngine(
          List.of(
              new AlloyScrapTemplate(),
              new SingleMetalTemplate(),
              new WeldAlloyTemplate(),
              new MaterialUnitPlusFeeTemplate(),
              new PlanUpliftTemplate()));

  @Test
  @DisplayName("ALLOY_SCRAP：alloyPrice×blank − scrap×ratio + fee 计算正确")
  void alloyScrap() {
    Map<String, Object> inputs = Map.of(
        "alloyPrice", new BigDecimal("0.0689925679"),
        "blankWeight", new BigDecimal("70"),
        "netWeight", new BigDecimal("40"),
        "scrapPrice", new BigDecimal("0.0656122260"),
        "scrapRatio", new BigDecimal("0.92"),
        "processFee", new BigDecimal("0.305001"));

    CalcResult r = engine.evaluate("ALLOY_SCRAP", inputs);

    // alloyCost = 0.0689925679×70 = 4.829479753
    // scrapDeduction = 30×0.0656122260×0.92 = 1.810897438
    // unitPrice = 4.829479753 − 1.810897438 + 0.305001 = 3.323583315
    assertThat(r.unitPrice()).isCloseTo(new BigDecimal("3.323583"), within(TOLERANCE));
    assertThat(r.trace().getTemplateCode()).isEqualTo("ALLOY_SCRAP");
    assertThat(r.trace().getSteps()).containsKey("alloyCost = alloyPrice × blankWeight");
  }

  @Test
  @DisplayName("SINGLE_METAL：materialPrice × netWeight + processFee")
  void singleMetal() {
    Map<String, Object> inputs = Map.of(
        "materialPrice", new BigDecimal("90.0"),
        "netWeight", new BigDecimal("0.5"),
        "processFee", new BigDecimal("12.89"));

    CalcResult r = engine.evaluate("SINGLE_METAL", inputs);

    // 90 × 0.5 + 12.89 = 57.89
    assertThat(r.unitPrice()).isCloseTo(new BigDecimal("57.89"), within(TOLERANCE));
  }

  @Test
  @DisplayName("WELD_ALLOY：多金属配比 Σ(price×ratio) × netWeight + processFee")
  void weldAlloy() {
    Map<String, Object> inputs = Map.of(
        "metals", List.of(
            Map.of("price", new BigDecimal("90.0"), "ratio", new BigDecimal("0.6")),
            Map.of("price", new BigDecimal("21.0"), "ratio", new BigDecimal("0.4"))),
        "netWeight", new BigDecimal("0.1"),
        "processFee", new BigDecimal("0.5"));

    CalcResult r = engine.evaluate("WELD_ALLOY", inputs);

    // weighted = 90×0.6 + 21×0.4 = 62.4； material = 6.24; total = 6.74
    assertThat(r.unitPrice()).isCloseTo(new BigDecimal("6.74"), within(TOLERANCE));
  }

  @Test
  @DisplayName("WELD_ALLOY：配比总和 ≠ 1 抛 IllegalArgumentException")
  void weldAlloyRatioMustSumToOne() {
    Map<String, Object> inputs = Map.of(
        "metals", List.of(
            Map.of("price", new BigDecimal("90"), "ratio", new BigDecimal("0.5")),
            Map.of("price", new BigDecimal("21"), "ratio", new BigDecimal("0.3"))),
        "netWeight", new BigDecimal("0.1"));

    assertThatThrownBy(() -> engine.evaluate("WELD_ALLOY", inputs))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("配比");
  }

  @Test
  @DisplayName("MATERIAL_UNIT_PLUS_FEE：materialPrice × blankWeight + processFee")
  void materialUnitPlusFee() {
    Map<String, Object> inputs = Map.of(
        "materialPrice", new BigDecimal("17.0"),
        "blankWeight", new BigDecimal("0.05"),
        "processFee", new BigDecimal("0.20"));

    CalcResult r = engine.evaluate("MATERIAL_UNIT_PLUS_FEE", inputs);

    // 17 × 0.05 + 0.20 = 1.05
    assertThat(r.unitPrice()).isCloseTo(new BigDecimal("1.05"), within(TOLERANCE));
  }

  @Test
  @DisplayName("PLAN_UPLIFT：planPrice × (1 + upliftRatio)")
  void planUplift() {
    Map<String, Object> inputs = Map.of(
        "planPrice", new BigDecimal("100.0"),
        "upliftRatio", new BigDecimal("0.05"));

    CalcResult r = engine.evaluate("PLAN_UPLIFT", inputs);

    // 100 × 1.05 = 105.00
    assertThat(r.unitPrice()).isCloseTo(new BigDecimal("105.00"), within(TOLERANCE));
  }

  @Test
  @DisplayName("缺必填字段抛 IllegalArgumentException 且消息含字段名")
  void missingRequiredField() {
    assertThatThrownBy(() -> engine.evaluate("SINGLE_METAL",
        Map.of("netWeight", new BigDecimal("0.5"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("materialPrice");
  }

  @Test
  @DisplayName("未注册模板 code 抛异常")
  void unknownTemplate() {
    assertThatThrownBy(() -> engine.evaluate("UNKNOWN_CODE", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNKNOWN_CODE");
  }

  @Test
  @DisplayName("engineType 返回 TEMPLATE")
  void engineType() {
    assertThat(engine.engineType()).isEqualTo("TEMPLATE");
  }

  @Test
  @DisplayName("trace 包含输入与每一步中间值")
  void traceCapturesIntermediateSteps() {
    CalcResult r = engine.evaluate("PLAN_UPLIFT", Map.of(
        "planPrice", new BigDecimal("100"), "upliftRatio", new BigDecimal("0.1")));

    assertThat(r.trace().getInputs()).containsEntry("planPrice", new BigDecimal("100"));
    assertThat(r.trace().getSteps())
        .containsEntry("upliftFactor = 1 + upliftRatio", new BigDecimal("1.1"));
  }
}
