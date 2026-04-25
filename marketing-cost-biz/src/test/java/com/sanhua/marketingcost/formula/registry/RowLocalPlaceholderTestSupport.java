package com.sanhua.marketingcost.formula.registry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

/**
 * 跨测试类复用的 {@link RowLocalPlaceholderRegistry} mock 工厂。
 *
 * <p>把"V36 默认两个占位符 {@code __material} / {@code __scrap}"的 stub 代码集中到
 * 一处；各测试 setUp() 直接调 {@link #defaultRegistry()}，不再散落 duplicate stub。
 */
public final class RowLocalPlaceholderTestSupport {

  private RowLocalPlaceholderTestSupport() {}

  /**
   * 返回一个 mock 的 registry，行为和 {@code lp_row_local_placeholder} seed 一致：
   * <ul>
   *   <li>{@code isKnown("__material")} / {@code isKnown("__scrap")} → true；其他 → false</li>
   *   <li>{@code tokenNames()} → 两个 code 的 token 别名列表</li>
   *   <li>{@code displayNames()} → 两个 code 的中文显示名</li>
   * </ul>
   */
  public static RowLocalPlaceholderRegistry defaultRegistry() {
    RowLocalPlaceholderRegistry r = mock(RowLocalPlaceholderRegistry.class);
    when(r.tokenNames()).thenReturn(Map.of(
        "__material", List.of("材料含税价格", "材料价格"),
        "__scrap", List.of("废料含税价格", "废料价格")));
    when(r.displayNames()).thenReturn(Map.of(
        "__material", "材料含税价格",
        "__scrap", "废料含税价格"));
    when(r.isKnown("__material")).thenReturn(true);
    when(r.isKnown("__scrap")).thenReturn(true);
    return r;
  }

  /** 空 registry —— 完全不含占位符；给"公式里绝对不用占位符"的测试用 */
  public static RowLocalPlaceholderRegistry emptyRegistry() {
    RowLocalPlaceholderRegistry r = mock(RowLocalPlaceholderRegistry.class);
    when(r.tokenNames()).thenReturn(Map.of());
    when(r.displayNames()).thenReturn(Map.of());
    return r;
  }
}
