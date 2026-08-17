package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteTechnicianAssignmentRule;
import com.sanhua.marketingcost.mapper.QuoteTechnicianAssignmentRuleMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalTechnicianAssignmentRuleSourceTest {
  @Test
  void filtersExactOptionalDimensionsAndOrdersBySpecificityThenPriority() {
    QuoteTechnicianAssignmentRuleMapper mapper = mock(QuoteTechnicianAssignmentRuleMapper.class);
    LocalTechnicianAssignmentRuleSource source = new LocalTechnicianAssignmentRuleSource(mapper);
    LocalDate date = LocalDate.of(2026, 8, 15);
    when(mapper.selectEffectiveRules("COMMERCIAL", date)).thenReturn(List.of(
        rule("GENERAL", 601L, null, null, 1),
        rule("DEPT-LOW", 602L, "FI-SC-006", "亚太营销本部", 20),
        rule("DEPT-HIGH", 603L, "FI-SC-006", "亚太营销本部", 10),
        rule("OTHER", 604L, "FI-SC-020", "欧美业务管理部", 1)));
    OaForm form = new OaForm();
    form.setProcessCode("FI-SC-006");
    form.setApplicantDept("亚太营销本部");

    List<TechnicianAssignmentRuleSource.RuleCandidate> matches = source.findMatches(
        TechnicianAssignmentContext.of(form, new OaFormItem(), "COMMERCIAL"), date);

    assertThat(matches).extracting(TechnicianAssignmentRuleSource.RuleCandidate::ruleCode)
        .containsExactly("DEPT-HIGH", "DEPT-LOW", "GENERAL");
    assertThat(matches).extracting(TechnicianAssignmentRuleSource.RuleCandidate::specificity)
        .containsExactly(2, 2, 0);
  }

  private QuoteTechnicianAssignmentRule rule(
      String code, Long userId, String process, String department, int priority) {
    QuoteTechnicianAssignmentRule rule = new QuoteTechnicianAssignmentRule();
    rule.setRuleCode(code);
    rule.setTechnicianUserId(userId);
    rule.setProcessCode(process);
    rule.setApplicantDepartment(department);
    rule.setPriority(priority);
    return rule;
  }
}
