package com.sanhua.marketingcost.formula;

import java.util.Map;

/**
 * 公式引擎接口 —— 本期只实现 TEMPLATE 一种引擎类型；DSL/SCRIPT 等扩展型留待后续。
 *
 * <p>分层职责：
 * <ul>
 *   <li>FormulaEngine 拿到模板 code + 入参 → 校验 + 计算 + 返回单价与 trace</li>
 *   <li>不负责取数（取数走 VariableRegistry，由调用方先把数解出来）</li>
 *   <li>不负责持久化（结果由调用方写入 lp_cost_run_part_item / 审计表）</li>
 * </ul>
 */
public interface FormulaEngine {

  /** 该引擎类型标识，与 lp_formula_template.engine_type 一致 */
  String engineType();

  /**
   * 校验入参 —— 缺字段 / 类型错误时抛 {@link IllegalArgumentException}。
   *
   * @param templateCode 模板编码
   * @param inputs       已解出的变量值（VariableRegistry 输出）
   */
  void validate(String templateCode, Map<String, Object> inputs);

  /**
   * 执行计算。
   *
   * @param templateCode 模板编码
   * @param inputs       已解出的变量值
   * @return 单价 + trace；遇缺数据可抛 {@link IllegalArgumentException}（不要返回 null）
   */
  CalcResult evaluate(String templateCode, Map<String, Object> inputs);
}
