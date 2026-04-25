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
 * 制造件递归取价测试 (Task #8)。
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
  @DisplayName("递归：父件 raw_unit_price 缺失时取上游 spec")
  void recursiveLookup() {
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
    // X2 单价 = (50×70 - 2×30)/1000 = 3.44
    // X1 单价 = (100×3.44 - 5×20×1)/1000 = (344 - 100)/1000 = 0.244
    assertThat(price).isCloseTo(new BigDecimal("0.244"), within(TOLERANCE));
  }

  @Test
  @DisplayName("循环检测：A → B → A 返回 null（不抛异常）")
  void cycleReturnsNull() {
    MakePartSpec a = newSpec("A", new BigDecimal("10"), new BigDecimal("9"), null,
        new BigDecimal("1"));
    a.setRawMaterialCode("B");
    MakePartSpec b = newSpec("B", new BigDecimal("10"), new BigDecimal("9"), null,
        new BigDecimal("1"));
    b.setRawMaterialCode("A");
    MakePartResolver resolver = build(List.of(a, b));
    assertThat(resolver.resolve("A", null)).isNull();
  }

  @Test
  @DisplayName("深度上限：>10 层返回 null")
  void depthLimitReturnsNull() {
    // 构造 12 个互相串联的 spec：N1 → N2 → ... → N12（终点无 raw price）
    int n = 12;
    MakePartSpec[] all = new MakePartSpec[n];
    for (int i = 0; i < n; i++) {
      all[i] = newSpec("N" + i,
          new BigDecimal("100"), new BigDecimal("99"),
          null, new BigDecimal("1"));
      if (i < n - 1) {
        all[i].setRawMaterialCode("N" + (i + 1));
      }
    }
    MakePartResolver resolver = build(List.of(all));
    assertThat(resolver.resolve("N0", null)).isNull();
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
