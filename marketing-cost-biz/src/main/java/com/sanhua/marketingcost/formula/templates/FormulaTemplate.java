package com.sanhua.marketingcost.formula.templates;

import com.sanhua.marketingcost.formula.CalcResult;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 单个公式模板的契约 —— TemplateEngine 把 (templateCode, inputs) 派发给对应 FormulaTemplate。
 *
 * <p>每个实现：
 * <ol>
 *   <li>声明 templateCode（与 lp_formula_template.template_code 一致）</li>
 *   <li>校验入参字段 + 类型（在 evaluate 内 fail-fast）</li>
 *   <li>执行公式骨架 + 写 trace</li>
 * </ol>
 */
public interface FormulaTemplate {

  /** 模板编码 */
  String templateCode();

  /**
   * 计算并返回 trace。要求所有数值用 {@link BigDecimal} 保留精度，禁用 double。
   *
   * <p>可在内部 helper 里读 BigDecimal 字段、设默认值，并在缺必填时抛
   * {@link IllegalArgumentException}。
   */
  CalcResult evaluate(Map<String, Object> inputs);
}
