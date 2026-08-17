package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.mapper.SysUserMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 报价技术负责人统一解析入口；页面和任务服务不得直接读取规则表。 */
@Component
public class CollaborationTechnicianResolver {
  private final SysUserMapper userMapper;
  private final TechnicianAssignmentRuleSource ruleSource;

  public CollaborationTechnicianResolver(
      SysUserMapper userMapper, TechnicianAssignmentRuleSource ruleSource) {
    this.userMapper = userMapper;
    this.ruleSource = ruleSource;
  }

  public Resolution resolve(
      OaForm form, OaFormItem item, String businessUnitType, Long overrideUserId) {
    if (overrideUserId != null) return validateExplicit(overrideUserId, businessUnitType);

    List<SysUser> legacyUsers = legacyUsers(item, businessUnitType);
    if (legacyUsers.size() == 1) return Resolution.success(legacyUsers.get(0));

    List<TechnicianAssignmentRuleSource.RuleCandidate> topRules = topRules(
        ruleSource.findMatches(TechnicianAssignmentContext.of(form, item, businessUnitType),
            LocalDate.now()));
    Map<Long, SysUser> validRuleUsers = new LinkedHashMap<>();
    for (TechnicianAssignmentRuleSource.RuleCandidate rule : topRules) {
      if (rule.technicianUserId() == null) continue;
      SysUser user = userMapper.selectActiveByIdAndBusinessUnit(
          rule.technicianUserId(), businessUnitType);
      if (user != null) validRuleUsers.putIfAbsent(user.getUserId(), user);
    }
    if (validRuleUsers.size() == 1) return Resolution.success(validRuleUsers.values().iterator().next());
    if (validRuleUsers.size() > 1) {
      return Resolution.failure("最高优先级负责人规则匹配到多名技术人员，请手工指定");
    }
    if (legacyUsers.size() > 1) {
      return Resolution.failure("技术负责人“" + item.getTechnicianName().trim()
          + "”匹配到多个账号，请手工指定并整理账号");
    }
    if (item != null && StringUtils.hasText(item.getTechnicianName())) {
      return Resolution.failure("技术负责人“" + item.getTechnicianName().trim()
          + "”未匹配到有效账号，本地规则也未命中，请手工指定");
    }
    return Resolution.failure("未匹配到技术负责人，请手工指定");
  }

  /** 兼容无报价表头的既有调用；生产投影和任务发起应传入完整表头。 */
  public Resolution resolve(OaFormItem item, String businessUnitType, Long overrideUserId) {
    return resolve(null, item, businessUnitType, overrideUserId);
  }

  public CandidateList candidates(OaForm form, OaFormItem item, String businessUnitType) {
    List<TechnicianAssignmentRuleSource.RuleCandidate> topRules = topRules(
        ruleSource.findMatches(TechnicianAssignmentContext.of(form, item, businessUnitType),
            LocalDate.now()));
    Set<Long> recommendedIds = topRules.stream()
        .map(TechnicianAssignmentRuleSource.RuleCandidate::technicianUserId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
    List<SysUser> legacyUsers = legacyUsers(item, businessUnitType);
    if (legacyUsers.size() == 1) recommendedIds.add(legacyUsers.get(0).getUserId());

    Map<Long, TechnicianAssignmentRuleSource.RuleCandidate> ruleByUser = new HashMap<>();
    for (TechnicianAssignmentRuleSource.RuleCandidate rule : topRules) {
      if (rule.technicianUserId() != null) ruleByUser.putIfAbsent(rule.technicianUserId(), rule);
    }
    List<Candidate> candidates = userMapper.selectActiveCollaboratorsByBusinessUnit(
        businessUnitType).stream().map(user -> {
          TechnicianAssignmentRuleSource.RuleCandidate rule = ruleByUser.get(user.getUserId());
          return new Candidate(user.getUserId(), displayName(user), user.getUserName(),
              rule == null ? null : rule.technicianOaUserId(),
              rule == null ? null : rule.technicianJobNo(),
              recommendedIds.contains(user.getUserId()),
              rule == null ? null : rule.ruleCode(),
              rule == null ? null : rule.ruleName());
        }).toList();
    if (candidates.isEmpty()) {
      return new CandidateList("NO_CANDIDATES",
          "暂无可选技术负责人，请先维护报价系统账号、业务单元、技术协作角色及匹配规则", candidates);
    }
    long recommendedCount = candidates.stream().filter(Candidate::recommended).count();
    String status = recommendedCount == 1 ? "RECOMMENDED" : "MANUAL_REQUIRED";
    String message = recommendedCount == 1
        ? "系统已根据负责人规则推荐，可确认后发起补录"
        : recommendedCount > 1
            ? "存在多名同优先级推荐人员，请确认实际负责人"
            : "负责人规则未唯一命中，请选择实际技术负责人";
    return new CandidateList(status, message, candidates);
  }

  private Resolution validateExplicit(Long userId, String businessUnitType) {
    SysUser user = userMapper.selectActiveByIdAndBusinessUnit(userId, businessUnitType);
    return user == null
        ? Resolution.failure("指定技术负责人不存在、已停用、不属于当前事业部或未配置技术协作角色")
        : Resolution.success(user);
  }

  private List<SysUser> legacyUsers(OaFormItem item, String businessUnitType) {
    if (item == null || !StringUtils.hasText(item.getTechnicianName())) return List.of();
    return userMapper.selectActiveByIdentityAndBusinessUnit(
        item.getTechnicianName().trim(), businessUnitType);
  }

  private static List<TechnicianAssignmentRuleSource.RuleCandidate> topRules(
      List<TechnicianAssignmentRuleSource.RuleCandidate> matches) {
    if (matches == null || matches.isEmpty()) return List.of();
    List<TechnicianAssignmentRuleSource.RuleCandidate> ordered = matches.stream()
        .sorted(Comparator.comparingInt(
                TechnicianAssignmentRuleSource.RuleCandidate::specificity).reversed()
            .thenComparingInt(TechnicianAssignmentRuleSource.RuleCandidate::priority)
            .thenComparing(TechnicianAssignmentRuleSource.RuleCandidate::ruleCode))
        .toList();
    TechnicianAssignmentRuleSource.RuleCandidate first = ordered.get(0);
    return ordered.stream()
        .filter(rule -> rule.specificity() == first.specificity()
            && rule.priority() == first.priority())
        .toList();
  }

  private static String displayName(SysUser user) {
    return StringUtils.hasText(user.getNickName()) ? user.getNickName() : user.getUserName();
  }

  public record Resolution(Long userId, String userName, String error) {
    static Resolution success(SysUser user) {
      return new Resolution(user.getUserId(), displayName(user), null);
    }
    static Resolution failure(String error) { return new Resolution(null, null, error); }
    public boolean resolved() { return userId != null; }
  }

  public record Candidate(
      Long userId,
      String userName,
      String loginName,
      String oaUserId,
      String jobNo,
      boolean recommended,
      String ruleCode,
      String ruleName) {}

  public record CandidateList(String matchStatus, String message, List<Candidate> candidates) {}
}
