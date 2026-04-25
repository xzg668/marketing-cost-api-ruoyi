package com.sanhua.marketingcost.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * LinkedParserProperties 绑定测试 —— 用 Binder 直接绑定 Map，避免拉起 SpringBootTest
 * 整个应用（项目 SpringBootTest 会连 DB，纯配置测试不必付这个代价）。
 */
class LinkedParserPropertiesTest {

  @Test
  @DisplayName("YAML 绑定：mode=dual，threshold=0.05")
  void bindsDualMode() {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
        "cost.linked.parser.mode", "dual",
        "cost.linked.parser.dual-warn-threshold", "0.05"));
    LinkedParserProperties properties = new Binder(source)
        .bind("cost.linked.parser", LinkedParserProperties.class)
        .orElseThrow(() -> new IllegalStateException("bind failed"));

    assertThat(properties.getMode()).isEqualTo("dual");
    assertThat(properties.getDualWarnThreshold())
        .isEqualByComparingTo(new BigDecimal("0.05"));
    assertThat(properties.runsNewParser()).isTrue();
    assertThat(properties.runsLegacyParser()).isTrue();
  }

  @Test
  @DisplayName("默认值：未提供配置时 mode=dual, threshold=0.01")
  void defaultsWhenEmpty() {
    LinkedParserProperties p = new LinkedParserProperties();
    assertThat(p.getMode()).isEqualTo("dual");
    assertThat(p.getDualWarnThreshold())
        .isEqualByComparingTo(new BigDecimal("0.01"));
  }

  @Test
  @DisplayName("legacy 模式仅走老路径")
  void legacyMode() {
    LinkedParserProperties p = new LinkedParserProperties();
    p.setMode("legacy");
    assertThat(p.runsLegacyParser()).isTrue();
    assertThat(p.runsNewParser()).isFalse();
  }

  @Test
  @DisplayName("new 模式仅走新路径")
  void newMode() {
    LinkedParserProperties p = new LinkedParserProperties();
    p.setMode("new");
    assertThat(p.runsLegacyParser()).isFalse();
    assertThat(p.runsNewParser()).isTrue();
  }
}
