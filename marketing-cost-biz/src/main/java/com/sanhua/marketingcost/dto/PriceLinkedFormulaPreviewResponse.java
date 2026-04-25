package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 联动公式预览响应体 —— 给前端展示 Normalizer + Registry 逐步过程。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code normalizedExpr}：规范化后的表达式（变量已 {@code [code]} 标签化）；
 *       解析失败时为 {@code null}</li>
 *   <li>{@code variables}：实际参与求值的变量明细；包含 {@code code/name/value/source}</li>
 *   <li>{@code result}：最终求值结果；解析失败或任一变量缺失严重时为 {@code null}</li>
 *   <li>{@code trace}：任务 #11 约定的 trace 数组结构（原样透传）</li>
 *   <li>{@code warnings}：非致命提示，如"未提供 materialCode 无法取部品上下文"</li>
 *   <li>{@code error}：致命错误信息（语法错误/循环引用），有值则 {@code result=null}</li>
 * </ul>
 */
public class PriceLinkedFormulaPreviewResponse {

  private String normalizedExpr;
  private List<VariableDetail> variables = new ArrayList<>();
  private BigDecimal result;
  private List<TraceEntry> trace = new ArrayList<>();
  private List<String> warnings = new ArrayList<>();
  private String error;

  public String getNormalizedExpr() {
    return normalizedExpr;
  }

  public void setNormalizedExpr(String normalizedExpr) {
    this.normalizedExpr = normalizedExpr;
  }

  public List<VariableDetail> getVariables() {
    return variables;
  }

  public void setVariables(List<VariableDetail> variables) {
    this.variables = variables;
  }

  public BigDecimal getResult() {
    return result;
  }

  public void setResult(BigDecimal result) {
    this.result = result;
  }

  public List<TraceEntry> getTrace() {
    return trace;
  }

  public void setTrace(List<TraceEntry> trace) {
    this.trace = trace;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<String> warnings) {
    this.warnings = warnings;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  /** 变量明细条目 —— 一个用在前端可展开面板的行数据。 */
  public static class VariableDetail {
    /** 变量代码（Normalizer tag 的 {@code [xxx]} 里的 xxx） */
    private String code;
    /** 变量中文名（来自 {@code lp_price_variable.variable_name}），缺失时同 code */
    private String name;
    /** 解析出的取值 */
    private BigDecimal value;
    /** 来源分类：{@code FINANCE_FACTOR / PART_CONTEXT / FORMULA_REF / CONST / MISSING} */
    private String source;

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public BigDecimal getValue() {
      return value;
    }

    public void setValue(BigDecimal value) {
      this.value = value;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }
  }

  /** trace 一条记录 —— 对应前端时间轴面板的一行。 */
  public static class TraceEntry {
    /** 阶段名：{@code normalize / resolve / evaluate / error} */
    private String step;
    /** 阶段细节（文本或 JSON 字符串） */
    private String detail;

    public TraceEntry() {}

    public TraceEntry(String step, String detail) {
      this.step = step;
      this.detail = detail;
    }

    public String getStep() {
      return step;
    }

    public void setStep(String step) {
      this.step = step;
    }

    public String getDetail() {
      return detail;
    }

    public void setDetail(String detail) {
      this.detail = detail;
    }
  }
}
