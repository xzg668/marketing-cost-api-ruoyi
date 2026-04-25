package com.sanhua.marketingcost.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 联动价 parser 灰度开关 —— 映射 {@code cost.linked.parser.*} 配置块。
 *
 * <p>模式说明：
 * <ul>
 *   <li>{@code legacy} — 仅跑旧 evaluateExpression（保留 [中文] 包裹兼容）</li>
 *   <li>{@code dual}   — legacy 主、new 并跑；差异 &gt; {@link #dualWarnThreshold} 打 WARN 日志，
 *       返回 legacy 结果（默认生产配置）</li>
 *   <li>{@code new}    — 仅跑新 FormulaNormalizer + VariableRegistry 管线；失败抛错</li>
 * </ul>
 *
 * <p>SpringBoot 会按字段名自动绑定；env 覆盖用 {@code COST_LINKED_PARSER_MODE=new}。
 */
@Component
@ConfigurationProperties(prefix = "cost.linked.parser")
public class LinkedParserProperties {

  /** 模式：legacy / dual / new */
  private String mode = "dual";

  /** 双跑差异阈值（元/件）；差值小于此值不打 WARN 避免噪声 */
  private BigDecimal dualWarnThreshold = new BigDecimal("0.01");

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public BigDecimal getDualWarnThreshold() {
    return dualWarnThreshold;
  }

  public void setDualWarnThreshold(BigDecimal dualWarnThreshold) {
    this.dualWarnThreshold = dualWarnThreshold;
  }

  /** 语义便捷方法：是否需要跑 new 管线（dual/new 都会跑） */
  public boolean runsNewParser() {
    return "dual".equalsIgnoreCase(mode) || "new".equalsIgnoreCase(mode);
  }

  /** 语义便捷方法：是否需要跑 legacy 管线（legacy/dual 都会跑） */
  public boolean runsLegacyParser() {
    return "legacy".equalsIgnoreCase(mode) || "dual".equalsIgnoreCase(mode);
  }
}
