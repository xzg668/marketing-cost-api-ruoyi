package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.system.SysDictData;
import com.sanhua.marketingcost.mapper.SysDictDataMapper;
import com.sanhua.marketingcost.service.BomLeafRollupCodesProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * T11 · {@link BomLeafRollupCodesProviderImpl} 5 单测。
 *
 * <p>不启 Spring（不依赖 cache），直接 new + Mockito 桩 mapper：
 * <ul>
 *   <li>每次调 matches/getCategoryCodes/getNameKeywords 都会真的访问 mapper（无 cache）</li>
 *   <li>覆盖 5 种命中情况：纯编码 / 纯名称 / 编码+名称 / 都不命中 / 字典空</li>
 * </ul>
 */
@DisplayName("T11 BomLeafRollupCodesProvider · 编码 + 名称兜底双路命中")
class BomLeafRollupCodesProviderImplTest {

  private SysDictDataMapper mapper;
  private BomLeafRollupCodesProvider provider;

  @BeforeEach
  void setUp() {
    mapper = Mockito.mock(SysDictDataMapper.class);
    provider = new BomLeafRollupCodesProviderImpl(mapper);
  }

  @Test
  @DisplayName("testCodeHit：字典只含编码 171711404，叶子 cat1=171711404 命中")
  void testCodeHit() {
    when(mapper.selectList(any())).thenReturn(List.of(dict("171711404", "编码-拉制铜管")));
    assertThat(provider.matches("171711404", "随便什么名")).isTrue();
    assertThat(provider.getCategoryCodes()).containsExactly("171711404");
    assertThat(provider.getNameKeywords()).isEmpty();
  }

  @Test
  @DisplayName("testNameKeywordHit：字典只含 NAME:拉制铜管，叶子 cat1=NULL+name 含关键词命中")
  void testNameKeywordHit() {
    when(mapper.selectList(any())).thenReturn(List.of(dict("NAME:拉制铜管", "名称兜底-拉制铜管")));
    assertThat(provider.matches(null, "拉制铜管 D8x0.5 L120")).isTrue();
    assertThat(provider.getNameKeywords()).containsExactly("拉制铜管");
    assertThat(provider.getCategoryCodes()).isEmpty();
  }

  @Test
  @DisplayName("testBothHit：编码 + NAME 都在字典，叶子任一路命中都返 true")
  void testBothHit() {
    when(mapper.selectList(any())).thenReturn(List.of(
        dict("171711404", "编码"),
        dict("NAME:拉制铜管", "名称")));
    // 仅靠编码命中
    assertThat(provider.matches("171711404", "无关名字")).isTrue();
    // 仅靠名称命中
    assertThat(provider.matches(null, "拉制铜管 D8")).isTrue();
    // 同时命中
    assertThat(provider.matches("171711404", "拉制铜管 D8")).isTrue();
  }

  @Test
  @DisplayName("testNoneHit：字典含编码 171711404，叶子 cat1=121191304+name=防尘帽 都不命中")
  void testNoneHit() {
    when(mapper.selectList(any())).thenReturn(List.of(dict("171711404", "编码")));
    assertThat(provider.matches("121191304", "防尘帽 100x50")).isFalse();
    // null + null 也不命中（防止 null=null 误命中）
    assertThat(provider.matches(null, null)).isFalse();
    // 空串两侧也不命中
    assertThat(provider.matches("", "")).isFalse();
  }

  @Test
  @DisplayName("testEmptyDict：字典全空，任何叶子都不命中")
  void testEmptyDict() {
    when(mapper.selectList(any())).thenReturn(List.of());
    assertThat(provider.matches("171711404", "拉制铜管")).isFalse();
    assertThat(provider.getCategoryCodes()).isEmpty();
    assertThat(provider.getNameKeywords()).isEmpty();
  }

  // ============================ 辅助 ============================

  /** 构造一条启用的字典数据（status=0；deleted 不设——@TableLogic 由 select 时过滤，单测层不关心） */
  private static SysDictData dict(String value, String label) {
    SysDictData d = new SysDictData();
    d.setDictType("bom_leaf_rollup_codes");
    d.setDictValue(value);
    d.setDictLabel(label);
    d.setStatus("0");
    return d;
  }
}
