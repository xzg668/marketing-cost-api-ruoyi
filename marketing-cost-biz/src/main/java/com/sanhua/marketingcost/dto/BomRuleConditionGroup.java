package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * BOM 规则中的一组复合条件。
 *
 * <p>组内条件按“本节点 AND 父节点 AND 至少一个子节点”判断；规则中的多个排除组按 OR
 * 判断，任意一组完整命中即排除当前规则。
 */
public class BomRuleConditionGroup {

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

  public boolean hasConditions() {
    return hasItems(nodeConditions) || hasItems(parentConditions) || hasItems(childConditions);
  }

  private static boolean hasItems(List<?> values) {
    return values != null && !values.isEmpty();
  }
}
