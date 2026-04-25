package com.sanhua.marketingcost.service.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.DrillRuleCondition;
import com.sanhua.marketingcost.dto.DrillRuleCondition.Clause;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * T8 新增：复合条件评估器。
 *
 * <p>解析 {@code bom_stop_drill_rule.match_condition_json} 的 JSON 条件，
 * 按 {@link DrillRuleCondition} 的三组条件语义判断是否命中：
 * <ul>
 *   <li>本节点条件 AND 父节点条件 AND "至少一个子节点满足"</li>
 *   <li>任一子组空则视为该组不参与判定（true by absence）</li>
 *   <li>规则顶层条件要求**父节点/子节点**时，若节点没有父/子节点，该组视为不满足</li>
 * </ul>
 *
 * <p>字段白名单见 {@link #readFieldValue(String, BomNodeContext)}；
 * 未识别的 field 或 op 都视为不命中 + log.warn。
 */
@Component
public class CompositeRuleEvaluator {

  private static final Logger log = LoggerFactory.getLogger(CompositeRuleEvaluator.class);

  private final ObjectMapper objectMapper;

  @Autowired
  public CompositeRuleEvaluator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * 评估节点 + 父 + 子集合是否满足规则 JSON 条件。
   *
   * @param conditionJson 规则的 match_condition_json 字段值；null 或空串直接返 false
   * @param node 本节点 context（必填）
   * @param parent 父节点 context；null 表示本节点是顶层
   * @param children 本节点的直接子节点 context 列表；允许空
   * @return true 命中
   */
  public boolean evaluate(
      String conditionJson,
      BomNodeContext node,
      BomNodeContext parent,
      List<BomNodeContext> children) {
    if (!StringUtils.hasText(conditionJson) || node == null) {
      return false;
    }
    DrillRuleCondition cond;
    try {
      cond = objectMapper.readValue(conditionJson, DrillRuleCondition.class);
    } catch (Exception e) {
      log.warn("match_condition_json 解析失败，跳过规则: {} err={}",
          conditionJson, e.getMessage());
      return false;
    }

    // 1) 本节点条件（AND）
    if (!matchAll(cond.getNodeConditions(), node)) return false;

    // 2) 父节点条件
    if (cond.getParentConditions() != null && !cond.getParentConditions().isEmpty()) {
      if (parent == null) return false; // 顶层节点不满足需要父的条件
      if (!matchAll(cond.getParentConditions(), parent)) return false;
    }

    // 3) 子节点条件（至少一个满足 —— OR-within-children, AND-within-clause）
    if (cond.getChildConditions() != null && !cond.getChildConditions().isEmpty()) {
      if (children == null || children.isEmpty()) return false;
      boolean anyChildHits =
          children.stream().anyMatch(c -> matchAll(cond.getChildConditions(), c));
      if (!anyChildHits) return false;
    }

    return true;
  }

  /** 一组 Clause AND 合并：全部满足返 true；空列表视为 true（无约束）。 */
  private boolean matchAll(List<Clause> clauses, BomNodeContext ctx) {
    if (clauses == null || clauses.isEmpty()) return true;
    if (ctx == null) return false;
    for (Clause c : clauses) {
      if (!matchOne(c, ctx)) return false;
    }
    return true;
  }

  /** 单个 Clause 判定 —— 按 op 分派。 */
  private boolean matchOne(Clause c, BomNodeContext ctx) {
    if (c == null) return false;
    String field = c.getField();
    String op = c.getOp();
    if (!StringUtils.hasText(field) || !StringUtils.hasText(op)) {
      log.warn("条件子句缺 field 或 op: field={} op={}", field, op);
      return false;
    }
    String actual = readFieldValue(field, ctx);
    return switch (op.toUpperCase()) {
      case "EQ" -> Objects.equals(actual, c.getValue());
      case "IN" -> {
        if (actual == null || c.getValues() == null || c.getValues().isEmpty()) yield false;
        yield c.getValues().contains(actual);
      }
      case "LIKE" -> actual != null && c.getValue() != null && actual.contains(c.getValue());
      default -> {
        log.warn("未知 op={} 视为不命中", op);
        yield false;
      }
    };
  }

  /**
   * 字段白名单 + 从 BomNodeContext 读对应值。未识别的 field 返 null + warn。
   *
   * <p>字段名用 snake_case 对齐数据库 / JSON 习惯；代码里的 record field 是 camelCase。
   */
  private String readFieldValue(String field, BomNodeContext ctx) {
    return switch (field) {
      case "material_code" -> ctx.materialCode();
      case "material_name" -> ctx.materialName();
      case "material_category" -> ctx.materialCategory();
      case "material_category_1" -> ctx.materialCategory1();
      case "material_category_2" -> ctx.materialCategory2();
      case "cost_element_code" -> ctx.costElementCode();
      case "shape_attr" -> ctx.shapeAttr();
      case "production_category" -> ctx.productionCategory();
      case "business_unit_type" -> ctx.businessUnitType();
      default -> {
        log.warn("未识别的条件字段 field={}（支持列表见 CompositeRuleEvaluator.readFieldValue）", field);
        yield null;
      }
    };
  }
}
