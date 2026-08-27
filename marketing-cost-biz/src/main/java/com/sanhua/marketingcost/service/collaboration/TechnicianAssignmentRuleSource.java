package com.sanhua.marketingcost.service.collaboration;

import java.time.LocalDate;
import java.util.List;

/**
 * 技术负责人规则来源扩展点。负责人规则改为配置表时替换实现，调用方不变。
 */
public interface TechnicianAssignmentRuleSource {
  List<RuleCandidate> findMatches(TechnicianAssignmentContext context, LocalDate asOfDate);

  record RuleCandidate(
      Long technicianUserId,
      String technicianOaUserId,
      String technicianJobNo,
      String ruleCode,
      String ruleName,
      int specificity,
      int priority) {}
}
