package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.mapper.MakePartSpecMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 制造件取价测试 (Task #8 historical skeleton)。
 *
 * <p>金标对照：S接管 (203250749) 期望 6.658 ± 0.01；本测试用真实参数验证默认骨架。
 */
class MakePartResolverTest {

  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  @Test
  @DisplayName("默认骨架：S接管 6.658 命中（金标 0.01 内）")
  void defaultSkeletonShfPipe() {
    MakePartSpec spec = newSpec("203250749",
        new BigDecimal("83.0538852"),     // blank g
        new BigDecimal("80.0"),            // net g
        new BigDecimal("82.94690265486727"), // raw 元/kg
        new BigDecimal("75.6637168141593")); // recycle 元/kg
    MakePartResolver resolver = build(List.of(spec));
    BigDecimal price = resolver.resolve("203250749", null);
    assertThat(price).isCloseTo(new BigDecimal("6.658"), within(TOLERANCE));
  }

  @Test
  @DisplayName("默认骨架：含 processFee + outsourceFee")
  void includeProcessAndOutsourceFee() {
    MakePartSpec spec = newSpec("X1",
        new BigDecimal("100"), new BigDecimal("90"),
        new BigDecimal("60"),  new BigDecimal("30"));
    spec.setProcessFee(new BigDecimal("0.5"));
    spec.setOutsourceFee(new BigDecimal("0.2"));
    MakePartResolver resolver = build(List.of(spec));
    BigDecimal price = resolver.resolve("X1", null);
    // (100×60 - 10×30×1)/1000 + 0.5 + 0.2 = (6000 - 300)/1000 + 0.7 = 5.7 + 0.7 = 6.4
    assertThat(price).isCloseTo(new BigDecimal("6.4"), within(TOLERANCE));
  }

  @Test
  @DisplayName("raw_unit_price 缺失时不拿 raw_material_code 递归取价")
  void rawMaterialCodeDoesNotTriggerRecursiveLookup() {
    // 子件 X2 有 raw price
    MakePartSpec child = newSpec("X2",
        new BigDecimal("50"), new BigDecimal("48"),
        new BigDecimal("70"), new BigDecimal("30"));
    // 父件 X1 raw price 留空，指向 X2
    MakePartSpec parent = newSpec("X1",
        new BigDecimal("100"), new BigDecimal("95"),
        null, new BigDecimal("20"));
    parent.setRawMaterialCode("X2");
    MakePartResolver resolver = build(List.of(parent, child));
    BigDecimal price = resolver.resolve("X1", null);
    assertThat(price).isNull();
  }

  @Test
  @DisplayName("raw_unit_price 明确为 0 时按 0 价命中")
  void zeroRawUnitPriceIsHit() {
    MakePartSpec spec = newSpec("ZERO",
        new BigDecimal("100"), new BigDecimal("90"),
        BigDecimal.ZERO, new BigDecimal("30"));
    spec.setRawMaterialCode("RAW-IGNORED");
    spec.setProcessFee(new BigDecimal("0.5"));
    MakePartResolver resolver = build(List.of(spec));
    BigDecimal price = resolver.resolve("ZERO", null);
    // (100×0 - 10×30×1)/1000 + 0.5 = 0.2
    assertThat(price).isCloseTo(new BigDecimal("0.2"), within(TOLERANCE));
  }

  @Test
  @DisplayName("缺 spec：返回 null")
  void missingSpec() {
    MakePartResolver resolver = build(List.of());
    assertThat(resolver.resolve("UNKNOWN", null)).isNull();
  }

  // ---------- 辅助 ----------

  /** 构造 spy 版 resolver，按 materialCode 直接路由 lookupSpec —— 绕开 mybatis-plus wrapper 反射 */
  private MakePartResolver build(List<MakePartSpec> specs) {
    Map<String, MakePartSpec> map = new HashMap<>();
    for (MakePartSpec s : specs) {
      map.put(s.getMaterialCode(), s);
    }
    MakePartSpecMapper mapper = mock(MakePartSpecMapper.class);
    // TemplateEngine 不会被调用（formula_id 都为 null），传 null 即可
    MakePartResolver resolver = Mockito.spy(new MakePartResolver(mapper, null));
    Mockito.doAnswer(inv -> map.get((String) inv.getArgument(0)))
        .when(resolver).lookupSpec(Mockito.anyString(), Mockito.any());
    return resolver;
  }

  private MakePartSpec newSpec(String code, BigDecimal blank, BigDecimal net,
                                BigDecimal rawPrice, BigDecimal recyclePrice) {
    MakePartSpec s = new MakePartSpec();
    s.setMaterialCode(code);
    s.setBlankWeight(blank);
    s.setNetWeight(net);
    s.setRawUnitPrice(rawPrice);
    s.setRecycleUnitPrice(recyclePrice);
    s.setRecycleRatio(BigDecimal.ONE);
    s.setProcessFee(BigDecimal.ZERO);
    s.setOutsourceFee(BigDecimal.ZERO);
    return s;
  }
}
