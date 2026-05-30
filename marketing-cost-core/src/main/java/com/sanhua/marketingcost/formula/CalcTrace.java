package com.sanhua.marketingcost.formula;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公式计算审计 trace —— 记录"模板/输入/中间步骤/输出"，便于财务对账与回放。
 *
 * <p>设计原则：trace 不参与算逻辑，只是记账；步骤名用业务可读中文（"合金料价×下料重"等）。
 */
public final class CalcTrace {
  private final String templateCode;
  private final Map<String, Object> inputs = new LinkedHashMap<>();
  private final Map<String, BigDecimal> steps = new LinkedHashMap<>();

  public CalcTrace(String templateCode) {
    this.templateCode = templateCode;
  }

  /** 记录一项原始输入 */
  public CalcTrace input(String name, Object value) {
    inputs.put(name, value);
    return this;
  }

  /** 记录一项中间步骤的数值 */
  public CalcTrace step(String name, BigDecimal value) {
    steps.put(name, value);
    return this;
  }

  public String getTemplateCode() {
    return templateCode;
  }

  public Map<String, Object> getInputs() {
    return inputs;
  }

  public Map<String, BigDecimal> getSteps() {
    return steps;
  }

  @Override
  public String toString() {
    return "CalcTrace{template=" + templateCode + ", inputs=" + inputs + ", steps=" + steps + "}";
  }
}
