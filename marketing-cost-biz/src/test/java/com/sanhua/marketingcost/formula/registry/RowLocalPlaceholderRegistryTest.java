package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.RowLocalPlaceholder;
import com.sanhua.marketingcost.mapper.RowLocalPlaceholderMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RowLocalPlaceholderRegistry} 单测 —— 覆盖 DB 读取、JSON 解析、
 * 懒加载缓存、invalidate 重建、异常行容错几个关键行为。
 */
class RowLocalPlaceholderRegistryTest {

  private RowLocalPlaceholderMapper mapper;
  private RowLocalPlaceholderRegistry registry;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, RowLocalPlaceholder.class);
  }

  @BeforeEach
  void setUp() {
    mapper = mock(RowLocalPlaceholderMapper.class);
    registry = new RowLocalPlaceholderRegistry(mapper, new ObjectMapper());
  }

  @Test
  @DisplayName("基础加载：2 条 active 行 → 两个 map 各 2 条，isKnown 正确")
  void basicLoad() {
    when(mapper.selectList(any())).thenReturn(List.of(
        row("__material", "材料含税价格", "[\"材料含税价格\",\"材料价格\"]"),
        row("__scrap", "废料含税价格", "[\"废料含税价格\",\"废料价格\"]")));

    assertThat(registry.displayNames())
        .containsEntry("__material", "材料含税价格")
        .containsEntry("__scrap", "废料含税价格");
    assertThat(registry.tokenNames())
        .containsEntry("__material", List.of("材料含税价格", "材料价格"))
        .containsEntry("__scrap", List.of("废料含税价格", "废料价格"));
    assertThat(registry.isKnown("__material")).isTrue();
    assertThat(registry.isKnown("__scrap")).isTrue();
    assertThat(registry.isKnown("Cu")).isFalse();
    assertThat(registry.isKnown(null)).isFalse();
  }

  @Test
  @DisplayName("懒加载：同一实例反复访问，mapper 只被查 1 次")
  void lazyCache() {
    when(mapper.selectList(any())).thenReturn(List.of(
        row("__material", "材料含税价格", "[\"材料含税价格\"]")));

    registry.displayNames();
    registry.tokenNames();
    registry.isKnown("__material");

    verify(mapper, times(1)).selectList(any());
  }

  @Test
  @DisplayName("invalidate 后下一次访问重建快照（mapper 第二次被查）")
  void invalidateRebuilds() {
    when(mapper.selectList(any())).thenReturn(List.of(
        row("__material", "材料含税价格", "[\"材料含税价格\"]")));
    assertThat(registry.displayNames()).hasSize(1);

    // 模拟运维插了新占位符后调用 invalidate
    when(mapper.selectList(any())).thenReturn(List.of(
        row("__material", "材料含税价格", "[\"材料含税价格\"]"),
        row("__packaging", "包装费", "[\"包装费\"]")));
    registry.invalidate();

    assertThat(registry.displayNames())
        .containsKeys("__material", "__packaging")
        .hasSize(2);
    verify(mapper, times(2)).selectList(any());
  }

  @Test
  @DisplayName("扩展场景：新增 __packaging 自动被 isKnown 识别，tokenNames 返回配置列表")
  void extensionFlow() {
    when(mapper.selectList(any())).thenReturn(List.of(
        row("__material", "材料含税价格", "[\"材料含税价格\"]"),
        row("__packaging", "包装费", "[\"包装费\",\"包装价格\"]")));

    assertThat(registry.isKnown("__packaging")).isTrue();
    assertThat(registry.tokenNames().get("__packaging"))
        .containsExactly("包装费", "包装价格");
    assertThat(registry.displayNames().get("__packaging")).isEqualTo("包装费");
  }

  @Test
  @DisplayName("token_names_json 解析失败 —— 该行 display 仍加载，tokens 跳过（WARN）")
  void malformedTokenJsonStillLoadsDisplay() {
    when(mapper.selectList(any())).thenReturn(List.of(
        row("__bad", "坏占位符", "not-a-json")));

    // display 正常；tokens 该 key 不应出现
    assertThat(registry.displayNames()).containsEntry("__bad", "坏占位符");
    assertThat(registry.tokenNames()).doesNotContainKey("__bad");
  }

  @Test
  @DisplayName("display 空 → fallback 到 code 自身，避免 renderer 炸")
  void emptyDisplayFallbacksToCode() {
    when(mapper.selectList(any())).thenReturn(List.of(
        row("__x", "", "[\"x\"]")));

    assertThat(registry.displayNames()).containsEntry("__x", "__x");
  }

  @Test
  @DisplayName("code 空 → 整行跳过，不污染 map")
  void blankCodeSkipped() {
    when(mapper.selectList(any())).thenReturn(List.of(
        row(null, "显示", "[\"x\"]"),
        row("   ", "显示", "[\"x\"]"),
        row("__ok", "OK", "[\"ok\"]")));

    assertThat(registry.displayNames()).hasSize(1).containsKey("__ok");
  }

  @Test
  @DisplayName("token 列表含空串/前后空白 —— 过滤 + trim")
  void tokensTrimmedAndFiltered() {
    when(mapper.selectList(any())).thenReturn(List.of(
        row("__m", "材料", "[\"  材料价格  \", \"\", \"材料含税价格\"]")));

    assertThat(registry.tokenNames().get("__m"))
        .containsExactly("材料价格", "材料含税价格");
  }

  // ============================ 工具 ============================

  private static RowLocalPlaceholder row(String code, String display, String tokensJson) {
    RowLocalPlaceholder r = new RowLocalPlaceholder();
    r.setCode(code);
    r.setDisplayName(display);
    r.setTokenNamesJson(tokensJson);
    r.setStatus("active");
    return r;
  }
}
