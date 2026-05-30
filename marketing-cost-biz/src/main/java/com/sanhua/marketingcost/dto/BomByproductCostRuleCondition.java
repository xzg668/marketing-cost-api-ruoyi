package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 新 BOM 副产品附加结算行规则条件 DTO。
 *
 * <p>对应 {@code lp_bom_byproduct_cost_rule.match_condition_json}，只描述副产品上下文条件；
 * 是否额外补结算行仍由 {@code add_condition_type} 和后续业务服务共同决定。
 */
public class BomByproductCostRuleCondition {

  private List<BomRuleClause> byproductConditions = new ArrayList<>();

  public List<BomRuleClause> getByproductConditions() {
    return byproductConditions;
  }

  public void setByproductConditions(List<BomRuleClause> byproductConditions) {
    this.byproductConditions = byproductConditions;
  }
}
