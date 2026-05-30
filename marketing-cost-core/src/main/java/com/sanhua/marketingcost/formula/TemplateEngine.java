package com.sanhua.marketingcost.formula;

import com.sanhua.marketingcost.formula.templates.FormulaTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 模板引擎 —— 内置 5 个 FormulaTemplate 按 code 分发。
 *
 * <p>本期不接 schema_json：字段名直接由模板代码校验（fail-fast），schema_json 仅用于
 * 前端展示与文档；以后接 DSL/SCRIPT 引擎时再统一 schema 运行时校验。
 */
@Component
public class TemplateEngine implements FormulaEngine {

  /** templateCode → 模板实现 */
  private final Map<String, FormulaTemplate> templates;

  public TemplateEngine(List<FormulaTemplate> templateBeans) {
    Map<String, FormulaTemplate> map = new HashMap<>();
    for (FormulaTemplate t : templateBeans) {
      map.put(t.templateCode(), t);
    }
    this.templates = Map.copyOf(map);
  }

  @Override
  public String engineType() {
    return "TEMPLATE";
  }

  @Override
  public void validate(String templateCode, Map<String, Object> inputs) {
    FormulaTemplate template = requireTemplate(templateCode);
    // 轻量校验：调用 evaluate 的前半部分（requiredDecimal 会在缺字段时抛异常）
    // 完整类型校验下沉到 evaluate；本方法主要用于接入阶段的"模板是否存在 + 必填是否齐全"
    template.evaluate(inputs == null ? Map.of() : inputs);
  }

  @Override
  public CalcResult evaluate(String templateCode, Map<String, Object> inputs) {
    FormulaTemplate template = requireTemplate(templateCode);
    return template.evaluate(inputs == null ? Map.of() : inputs);
  }

  private FormulaTemplate requireTemplate(String templateCode) {
    FormulaTemplate template = templates.get(templateCode);
    if (template == null) {
      throw new IllegalArgumentException("未注册的公式模板: " + templateCode);
    }
    return template;
  }
}
