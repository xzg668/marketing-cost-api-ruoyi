package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.system.SysDictData;
import com.sanhua.marketingcost.mapper.SysDictDataMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * T11 增强 · {@link BomRawMaterialCostElementsProviderImpl} 单测。
 *
 * <p>不启 Spring，用 Mockito 桩 SysDictDataMapper；覆盖编码白名单的 4 个核心场景。
 */
@DisplayName("T11 BomRawMaterialCostElementsProvider · 原材料编码白名单")
@ExtendWith(MockitoExtension.class)
// missForNullOrEmpty 测试 null/空串 short-circuit 不会查 mapper，setUp 默认 stub 用不上
//   → strict mode 会抛 UnnecessaryStubbing；显式 LENIENT 接受这种"防御性 stub"
@MockitoSettings(strictness = Strictness.LENIENT)
class BomRawMaterialCostElementsProviderImplTest {

  @Mock private SysDictDataMapper mapper;

  @InjectMocks private BomRawMaterialCostElementsProviderImpl provider;

  /** 帮助：构造一条字典数据 */
  private static SysDictData entry(String value) {
    SysDictData d = new SysDictData();
    d.setDictValue(value);
    return d;
  }

  @BeforeEach
  void setUp() {
    // 默认字典：1 条种子 No101（仿 V44 SQL）
    when(mapper.selectList(any())).thenReturn(List.of(entry("No101")));
  }

  @Test
  @DisplayName("isRawMaterial：编码命中白名单返 true")
  void hitsWhenInWhitelist() {
    assertThat(provider.isRawMaterial("No101")).isTrue();
  }

  @Test
  @DisplayName("isRawMaterial：编码不在白名单返 false")
  void missWhenOutsideWhitelist() {
    assertThat(provider.isRawMaterial("No102")).isFalse();
    assertThat(provider.isRawMaterial("No103")).isFalse();
  }

  @Test
  @DisplayName("isRawMaterial：null / 空串返 false（不应命中字典里的 NULL）")
  void missForNullOrEmpty() {
    assertThat(provider.isRawMaterial(null)).isFalse();
    assertThat(provider.isRawMaterial("")).isFalse();
  }

  @Test
  @DisplayName("isRawMaterial：字典空时所有调用都返 false（不会 NPE）")
  void missWhenDictEmpty() {
    when(mapper.selectList(any())).thenReturn(List.of());
    assertThat(provider.isRawMaterial("No101")).isFalse();
    assertThat(provider.isRawMaterial(null)).isFalse();
  }

  @Test
  @DisplayName("isRawMaterial：字典多条种子（业务后续扩展）能识别")
  void hitsWhenMultiSeeds() {
    when(mapper.selectList(any())).thenReturn(
        List.of(entry("No101"), entry("No311"), entry("CUSTOM_RAW")));
    assertThat(provider.isRawMaterial("No101")).isTrue();
    assertThat(provider.isRawMaterial("No311")).isTrue();
    assertThat(provider.isRawMaterial("CUSTOM_RAW")).isTrue();
    assertThat(provider.isRawMaterial("No102")).isFalse();
  }
}
