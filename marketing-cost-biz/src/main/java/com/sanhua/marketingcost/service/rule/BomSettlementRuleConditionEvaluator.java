package com.sanhua.marketingcost.service.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.BomRuleClause;
import com.sanhua.marketingcost.dto.BomSettlementRuleCondition;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 新 BOM 树节点结算规则 JSON 条件评估器。
 *
 * <p>本类只解析 {@code lp_bom_settlement_rule.match_condition_json} 并判断节点条件是否命中。
 * 规则优先级由 Matcher 统一排序；空条件组表示该组不限制，缺父/缺子但规则要求父/子时视为不命中。
 */
@Component
public class BomSettlementRuleConditionEvaluator {

  private static final Logger log =
      LoggerFactory.getLogger(BomSettlementRuleConditionEvaluator.class);

  private final ObjectMapper objectMapper;

  public BomSettlementRuleConditionEvaluator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public boolean evaluate(
      String conditionJson,
      BomRuleNodeContext node,
      BomRuleNodeContext parent,
      List<BomRuleNodeContext> children) {
    if (!StringUtils.hasText(conditionJson) || node == null) {
      return false;
    }

    BomSettlementRuleCondition condition;
    try {
      condition = objectMapper.readValue(conditionJson, BomSettlementRuleCondition.class);
    } catch (Exception e) {
      log.warn("BOM 结算规则 JSON 解析失败，跳过规则: {} err={}", conditionJson, e.getMessage());
      return false;
    }

    if (!matchAll(condition.getNodeConditions(), node)) {
      return false;
    }
    if (condition.getParentConditions() != null && !condition.getParentConditions().isEmpty()) {
      if (parent == null) {
        return false;
      }
      if (!matchAll(condition.getParentConditions(), parent)) {
        return false;
      }
    }
    if (condition.getChildConditions() != null && !condition.getChildConditions().isEmpty()) {
      if (children == null || children.isEmpty()) {
        return false;
      }
      return children.stream().anyMatch(child -> matchAll(condition.getChildConditions(), child));
    }
    return true;
  }

  private boolean matchAll(List<BomRuleClause> clauses, BomRuleNodeContext context) {
    if (clauses == null || clauses.isEmpty()) {
      return true;
    }
    if (context == null) {
      return false;
    }
    for (BomRuleClause clause : clauses) {
      if (!matchOne(clause, context)) {
        return false;
      }
    }
    return true;
  }

  private boolean matchOne(BomRuleClause clause, BomRuleNodeContext context) {
    if (clause == null
        || !StringUtils.hasText(clause.getField())
        || !StringUtils.hasText(clause.getOp())) {
      log.warn("BOM 结算规则条件子句缺 field 或 op");
      return false;
    }
    String actual = trimToNull(readFieldValue(clause.getField(), context));
    String expected = trimToNull(clause.getValue());
    List<String> expectedValues = normalizeValues(clause.getValues());
    return switch (clause.getOp().toUpperCase()) {
      case "EQ" -> Objects.equals(actual, expected);
      case "NE" -> !Objects.equals(actual, expected);
      case "IN" -> actual != null
          && clause.getValues() != null
          && expectedValues.contains(actual);
      // 保留既有 NOT_IN 的空值语义，避免改变财务辅料排除规则；需要必填时显式增加 NOT_BLANK。
      case "NOT_IN" -> clause.getValues() != null
          && (actual == null || !expectedValues.contains(actual));
      case "NOT_BLANK" -> actual != null;
      case "LIKE" -> actual != null
          && expected != null
          && actual.contains(expected);
      case "PREFIX" -> actual != null
          && expected != null
          && actual.startsWith(expected);
      default -> {
        log.warn("BOM 结算规则未知 op={}，视为不命中", clause.getOp());
        yield false;
      }
    };
  }

  private static List<String> normalizeValues(List<String> values) {
    if (values == null) {
      return Collections.emptyList();
    }
    return values.stream()
        .map(BomSettlementRuleConditionEvaluator::trimToNull)
        .filter(Objects::nonNull)
        .toList();
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String readFieldValue(String field, BomRuleNodeContext context) {
    return switch (field) {
      case "material_code" -> context.materialCode();
      case "material_name" -> context.materialName();
      case "material_category_code" -> context.materialCategoryCode();
      case "main_category_code" -> context.mainCategoryCode();
      case "main_category_name" -> context.mainCategoryName();
      case "purchase_category" -> context.purchaseCategory();
      case "shape_attr" -> context.shapeAttr();
      case "cost_element_code" -> context.costElementCode();
      case "production_category" -> context.productionCategory();
      case "business_unit_type" -> context.businessUnitType();
      case "bom_purpose" -> context.bomPurpose();
      default -> {
        log.warn("BOM 结算规则未识别字段 field={}，视为不命中", field);
        yield null;
      }
    };
  }
}
