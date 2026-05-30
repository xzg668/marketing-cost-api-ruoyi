package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 新 BOM 树节点结算规则条件 DTO。
 *
 * <p>对应 {@code lp_bom_settlement_rule.match_condition_json}，避免继续沿用旧
 * 旧过滤规则 DTO 命名。三组条件语义为：本节点 AND 父节点 AND 至少一个子节点。
 */
public class BomSettlementRuleCondition {

  private List<BomRuleClause> nodeConditions = new ArrayList<>();
  private List<BomRuleClause> parentConditions = new ArrayList<>();
  private List<BomRuleClause> childConditions = new ArrayList<>();

  public List<BomRuleClause> getNodeConditions() {
    return nodeConditions;
  }

  public void setNodeConditions(List<BomRuleClause> nodeConditions) {
    this.nodeConditions = nodeConditions;
  }

  public List<BomRuleClause> getParentConditions() {
    return parentConditions;
  }

  public void setParentConditions(List<BomRuleClause> parentConditions) {
    this.parentConditions = parentConditions;
  }

  public List<BomRuleClause> getChildConditions() {
    return childConditions;
  }

  public void setChildConditions(List<BomRuleClause> childConditions) {
    this.childConditions = childConditions;
  }
}
