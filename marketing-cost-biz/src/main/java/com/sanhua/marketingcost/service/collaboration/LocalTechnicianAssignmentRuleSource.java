package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteTechnicianAssignmentRule;
import com.sanhua.marketingcost.mapper.QuoteTechnicianAssignmentRuleMapper;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LocalTechnicianAssignmentRuleSource implements TechnicianAssignmentRuleSource {
  private final QuoteTechnicianAssignmentRuleMapper mapper;

  public LocalTechnicianAssignmentRuleSource(QuoteTechnicianAssignmentRuleMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<RuleCandidate> findMatches(
      TechnicianAssignmentContext context, LocalDate asOfDate) {
    if (context == null || !StringUtils.hasText(context.businessUnitType())) return List.of();
    return mapper.selectEffectiveRules(context.businessUnitType(), asOfDate).stream()
        .filter(rule -> matches(rule.getProcessCode(), context.processCode()))
        .filter(rule -> matches(rule.getSourceBusinessDivision(), context.sourceBusinessDivision()))
        .filter(rule -> matches(rule.getApplicantDepartment(), context.applicantDepartment()))
        .filter(rule -> matches(rule.getApplicantOffice(), context.applicantOffice()))
        .map(this::candidate)
        .sorted(Comparator.comparingInt(RuleCandidate::specificity).reversed()
            .thenComparingInt(RuleCandidate::priority)
            .thenComparing(RuleCandidate::ruleCode))
        .toList();
  }

  private RuleCandidate candidate(QuoteTechnicianAssignmentRule rule) {
    int specificity = count(rule.getProcessCode(), rule.getSourceBusinessDivision(),
        rule.getApplicantDepartment(), rule.getApplicantOffice());
    return new RuleCandidate(rule.getTechnicianUserId(), rule.getTechnicianOaUserId(),
        rule.getTechnicianJobNo(), rule.getRuleCode(), rule.getRuleName(), specificity,
        rule.getPriority() == null ? 100 : rule.getPriority());
  }

  private static boolean matches(String expected, String actual) {
    return !StringUtils.hasText(expected)
        || (StringUtils.hasText(actual) && expected.trim().equalsIgnoreCase(actual.trim()));
  }

  private static int count(String... values) {
    int count = 0;
    for (String value : values) if (StringUtils.hasText(value)) count++;
    return count;
  }
}
