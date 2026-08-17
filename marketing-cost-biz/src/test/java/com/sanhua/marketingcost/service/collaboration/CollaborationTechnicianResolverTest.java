package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.mapper.SysUserMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollaborationTechnicianResolverTest {
  private SysUserMapper userMapper;
  private TechnicianAssignmentRuleSource ruleSource;
  private CollaborationTechnicianResolver resolver;
  private OaForm form;
  private OaFormItem item;

  @BeforeEach
  void setUp() {
    userMapper = mock(SysUserMapper.class);
    ruleSource = mock(TechnicianAssignmentRuleSource.class);
    resolver = new CollaborationTechnicianResolver(userMapper, ruleSource);
    form = new OaForm();
    form.setProcessCode("FI-SC-006");
    item = new OaFormItem();
  }

  @Test
  void explicitUserMustBeAnActiveCollaboratorInCurrentBusinessUnit() {
    when(userMapper.selectActiveByIdAndBusinessUnit(602L, "COMMERCIAL"))
        .thenReturn(user(602L, "lig", "李工"));

    CollaborationTechnicianResolver.Resolution resolution =
        resolver.resolve(form, item, "COMMERCIAL", 602L);

    assertThat(resolution.resolved()).isTrue();
    assertThat(resolution.userName()).isEqualTo("李工");
    verify(userMapper).selectActiveByIdAndBusinessUnit(602L, "COMMERCIAL");
  }

  @Test
  void explicitCrossBusinessUnitOrUnauthorizedUserIsRejected() {
    when(userMapper.selectActiveByIdAndBusinessUnit(602L, "COMMERCIAL")).thenReturn(null);

    CollaborationTechnicianResolver.Resolution resolution =
        resolver.resolve(form, item, "COMMERCIAL", 602L);

    assertThat(resolution.resolved()).isFalse();
    assertThat(resolution.error()).contains("不存在", "已停用", "当前事业部", "技术协作角色");
  }

  @Test
  void existingOaTechnicianNameWinsWhenItUniquelyMaps() {
    item.setTechnicianName("王工");
    when(userMapper.selectActiveByIdentityAndBusinessUnit("王工", "COMMERCIAL"))
        .thenReturn(List.of(user(601L, "wang", "王工")));

    CollaborationTechnicianResolver.Resolution resolution =
        resolver.resolve(form, item, "COMMERCIAL", null);

    assertThat(resolution.userId()).isEqualTo(601L);
  }

  @Test
  void uniqueTopRuleResolvesAndEqualTopRulesForDifferentUsersRequireManualChoice() {
    TechnicianAssignmentContext context = TechnicianAssignmentContext.of(
        form, item, "COMMERCIAL");
    var first = rule(602L, "R-1", 2, 10);
    when(ruleSource.findMatches(context, LocalDate.now())).thenReturn(List.of(first));
    when(userMapper.selectActiveByIdAndBusinessUnit(602L, "COMMERCIAL"))
        .thenReturn(user(602L, "lig", "李工"));
    assertThat(resolver.resolve(form, item, "COMMERCIAL", null).userId()).isEqualTo(602L);

    var second = rule(603L, "R-2", 2, 10);
    when(ruleSource.findMatches(context, LocalDate.now())).thenReturn(List.of(first, second));
    when(userMapper.selectActiveByIdAndBusinessUnit(603L, "COMMERCIAL"))
        .thenReturn(user(603L, "zhao", "赵工"));
    CollaborationTechnicianResolver.Resolution ambiguous =
        resolver.resolve(form, item, "COMMERCIAL", null);
    assertThat(ambiguous.resolved()).isFalse();
    assertThat(ambiguous.error()).contains("多名技术人员", "手工指定");
  }

  @Test
  void candidateListContainsOnlyServerValidatedAccountsAndMarksRecommendations() {
    TechnicianAssignmentContext context = TechnicianAssignmentContext.of(
        form, item, "COMMERCIAL");
    when(ruleSource.findMatches(context, LocalDate.now())).thenReturn(List.of(
        new TechnicianAssignmentRuleSource.RuleCandidate(
            602L, "OA-602", "T602", "R-1", "商用技术", 2, 10)));
    when(userMapper.selectActiveCollaboratorsByBusinessUnit("COMMERCIAL")).thenReturn(List.of(
        user(601L, "wang", "王工"), user(602L, "lig", "李工")));

    CollaborationTechnicianResolver.CandidateList result =
        resolver.candidates(form, item, "COMMERCIAL");

    assertThat(result.matchStatus()).isEqualTo("RECOMMENDED");
    assertThat(result.candidates()).hasSize(2);
    assertThat(result.candidates()).filteredOn(CollaborationTechnicianResolver.Candidate::recommended)
        .singleElement().satisfies(candidate -> {
          assertThat(candidate.userId()).isEqualTo(602L);
          assertThat(candidate.oaUserId()).isEqualTo("OA-602");
          assertThat(candidate.jobNo()).isEqualTo("T602");
        });
  }

  @Test
  void noCandidateReturnsActionableConfigurationMessage() {
    when(ruleSource.findMatches(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    when(userMapper.selectActiveCollaboratorsByBusinessUnit("COMMERCIAL")).thenReturn(List.of());

    CollaborationTechnicianResolver.CandidateList result =
        resolver.candidates(form, item, "COMMERCIAL");

    assertThat(result.matchStatus()).isEqualTo("NO_CANDIDATES");
    assertThat(result.message()).contains("账号", "业务单元", "技术协作角色", "匹配规则");
  }

  private TechnicianAssignmentRuleSource.RuleCandidate rule(
      Long userId, String code, int specificity, int priority) {
    return new TechnicianAssignmentRuleSource.RuleCandidate(
        userId, null, null, code, code, specificity, priority);
  }

  private SysUser user(Long id, String login, String nickName) {
    SysUser user = new SysUser();
    user.setUserId(id);
    user.setUserName(login);
    user.setNickName(nickName);
    return user;
  }
}
