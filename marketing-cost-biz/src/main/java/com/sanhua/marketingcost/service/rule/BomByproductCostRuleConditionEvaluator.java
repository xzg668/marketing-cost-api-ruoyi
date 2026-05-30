package com.sanhua.marketingcost.service.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.BomByproductCostRuleCondition;
import com.sanhua.marketingcost.dto.BomRuleClause;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 新 BOM 副产品附加规则 JSON 条件评估器。
 *
 * <p>空 {@code match_condition_json} 表示副产品规则不再追加字段限制；空条件数组也视为 true。
 */
@Component
public class BomByproductCostRuleConditionEvaluator {

  private static final Logger log =
      LoggerFactory.getLogger(BomByproductCostRuleConditionEvaluator.class);

  private final ObjectMapper objectMapper;

  public BomByproductCostRuleConditionEvaluator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public boolean evaluate(String conditionJson, BomRuleNodeContext byproductContext) {
    if (byproductContext == null) {
      return false;
    }
    if (!StringUtils.hasText(conditionJson)) {
      return true;
    }

    BomByproductCostRuleCondition condition;
    try {
      condition = objectMapper.readValue(conditionJson, BomByproductCostRuleCondition.class);
    } catch (Exception e) {
      log.warn("BOM 副产品规则 JSON 解析失败，跳过规则: {} err={}", conditionJson, e.getMessage());
      return false;
    }
    return matchAll(condition.getByproductConditions(), byproductContext);
  }

  private boolean matchAll(List<BomRuleClause> clauses, BomRuleNodeContext context) {
    if (clauses == null || clauses.isEmpty()) {
      return true;
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
      log.warn("BOM 副产品规则条件子句缺 field 或 op");
      return false;
    }
    String actual = readFieldValue(clause.getField(), context);
    return switch (clause.getOp().toUpperCase()) {
      case "EQ" -> Objects.equals(actual, clause.getValue());
      case "IN" -> actual != null
          && clause.getValues() != null
          && clause.getValues().contains(actual);
      case "LIKE" -> actual != null
          && clause.getValue() != null
          && actual.contains(clause.getValue());
      case "PREFIX" -> actual != null
          && clause.getValue() != null
          && actual.startsWith(clause.getValue());
      default -> {
        log.warn("BOM 副产品规则未知 op={}，视为不命中", clause.getOp());
        yield false;
      }
    };
  }

  private String readFieldValue(String field, BomRuleNodeContext context) {
    return switch (field) {
      case "material_code" -> context.materialCode();
      case "material_name" -> context.materialName();
      case "material_category_code" -> context.materialCategoryCode();
      case "main_category_name" -> context.mainCategoryName();
      case "purchase_category" -> context.purchaseCategory();
      case "shape_attr" -> context.shapeAttr();
      case "cost_element_code" -> context.costElementCode();
      case "production_category" -> context.productionCategory();
      case "business_unit_type" -> context.businessUnitType();
      case "bom_purpose" -> context.bomPurpose();
      default -> {
        log.warn("BOM 副产品规则未识别字段 field={}，视为不命中", field);
        yield null;
      }
    };
  }
}
