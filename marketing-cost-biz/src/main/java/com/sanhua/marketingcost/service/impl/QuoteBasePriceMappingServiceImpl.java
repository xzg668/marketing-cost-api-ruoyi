package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.QuoteBasePriceDetectResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.QuoteBasePriceMappingRule;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorQuoteBaseMappingMapper;
import com.sanhua.marketingcost.mapper.QuoteBasePriceMappingRuleMapper;
import com.sanhua.marketingcost.service.QuoteBasePriceMappingService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteBasePriceMappingServiceImpl implements QuoteBasePriceMappingService {

  private static final String DEFAULT_MATCH_MODE = "ANY_KEYWORD";
  private static final String DEFAULT_MATCH_SOURCE = "AUTO";
  private static final String DEFAULT_CONFIDENCE = "HIGH";

  /** 当前阶段只允许 OA 表头已有且单位口径已确认的公共基价字段。 */
  private static final Set<String> SUPPORTED_QUOTE_FIELDS =
      Set.of("copper_price", "zinc_price", "aluminum_price");
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

  private final QuoteBasePriceMappingRuleMapper ruleMapper;
  private final FactorQuoteBaseMappingMapper mappingMapper;
  private final FactorIdentityMapper factorIdentityMapper;
  private final ObjectMapper objectMapper;

  public QuoteBasePriceMappingServiceImpl(
      QuoteBasePriceMappingRuleMapper ruleMapper,
      FactorQuoteBaseMappingMapper mappingMapper,
      FactorIdentityMapper factorIdentityMapper,
      ObjectMapper objectMapper) {
    this.ruleMapper = ruleMapper;
    this.mappingMapper = mappingMapper;
    this.factorIdentityMapper = factorIdentityMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public Page<QuoteBasePriceMappingRule> pageRules(
      String businessUnitType,
      String quoteFieldCode,
      String keyword,
      Boolean enabled,
      int page,
      int pageSize) {
    LambdaQueryWrapper<QuoteBasePriceMappingRule> query = ruleQuery();
    String bu = normalizeBusinessUnit(businessUnitType);
    if (StringUtils.hasText(bu)) {
      query.eq(QuoteBasePriceMappingRule::getBusinessUnitType, bu);
    }
    if (StringUtils.hasText(quoteFieldCode)) {
      query.eq(QuoteBasePriceMappingRule::getQuoteFieldCode, quoteFieldCode.trim());
    }
    if (enabled != null) {
      query.eq(QuoteBasePriceMappingRule::getEnabled, enabled ? 1 : 0);
    }
    if (StringUtils.hasText(keyword)) {
      String like = keyword.trim();
      query.and(w -> w.like(QuoteBasePriceMappingRule::getQuoteFieldName, like)
          .or().like(QuoteBasePriceMappingRule::getVariableCode, like)
          .or().like(QuoteBasePriceMappingRule::getMatchKeywordsJson, like)
          .or().like(QuoteBasePriceMappingRule::getRemark, like));
    }
    query.orderByAsc(QuoteBasePriceMappingRule::getBusinessUnitType)
        .orderByAsc(QuoteBasePriceMappingRule::getPriority)
        .orderByDesc(QuoteBasePriceMappingRule::getId);
    return ruleMapper.selectPage(new Page<>(page(page), size(pageSize)), query);
  }

  @Override
  public List<QuoteBasePriceMappingRule> listEnabledRules(
      String businessUnitType, String keyword) {
    LambdaQueryWrapper<QuoteBasePriceMappingRule> query = ruleQuery()
        .eq(QuoteBasePriceMappingRule::getEnabled, 1);
    String bu = normalizeBusinessUnit(businessUnitType);
    if (StringUtils.hasText(bu)) {
      // 查询某个业务单元时，同时带上全局默认规则。
      query.and(w -> w.eq(QuoteBasePriceMappingRule::getBusinessUnitType, "")
          .or().eq(QuoteBasePriceMappingRule::getBusinessUnitType, bu));
    }
    if (StringUtils.hasText(keyword)) {
      String like = keyword.trim();
      query.and(w -> w.like(QuoteBasePriceMappingRule::getQuoteFieldName, like)
          .or().like(QuoteBasePriceMappingRule::getVariableCode, like)
          .or().like(QuoteBasePriceMappingRule::getMatchKeywordsJson, like)
          .or().like(QuoteBasePriceMappingRule::getRemark, like));
    }
    query.orderByAsc(QuoteBasePriceMappingRule::getBusinessUnitType)
        .orderByAsc(QuoteBasePriceMappingRule::getPriority)
        .orderByDesc(QuoteBasePriceMappingRule::getId);
    return ruleMapper.selectList(query);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBasePriceMappingRule createRule(QuoteBasePriceMappingRule rule, String operator) {
    if (rule == null) {
      throw new IllegalArgumentException("规则不能为空");
    }
    normalizeRule(rule, operator, true);
    ruleMapper.insert(rule);
    return rule;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBasePriceMappingRule updateRule(
      Long id, QuoteBasePriceMappingRule patch, String operator) {
    if (id == null) {
      throw new IllegalArgumentException("规则 id 不能为空");
    }
    QuoteBasePriceMappingRule existing = ruleMapper.selectById(id);
    if (existing == null) {
      throw new IllegalArgumentException("规则不存在：id=" + id);
    }
    mergeRule(existing, patch);
    normalizeRule(existing, operator, false);
    ruleMapper.updateById(existing);
    return existing;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void setRuleEnabled(Long id, boolean enabled, String operator) {
    QuoteBasePriceMappingRule existing = requireRule(id);
    existing.setEnabled(enabled ? 1 : 0);
    touch(existing, operator);
    ruleMapper.updateById(existing);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteRule(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("规则 id 不能为空");
    }
    ruleMapper.deleteById(id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FactorQuoteBaseMapping saveMapping(FactorQuoteBaseMapping mapping, String operator) {
    if (mapping == null) {
      throw new IllegalArgumentException("识别结果不能为空");
    }
    normalizeMapping(mapping, operator, mapping.getId() == null);
    if (mapping.getId() == null) {
      mappingMapper.insert(mapping);
    } else {
      mappingMapper.updateById(mapping);
    }
    return mapping;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void setMappingEnabled(Long id, boolean enabled, String operator) {
    FactorQuoteBaseMapping existing = requireMapping(id);
    existing.setEnabled(enabled ? 1 : 0);
    touch(existing, operator);
    mappingMapper.updateById(existing);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteMapping(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("识别结果 id 不能为空");
    }
    mappingMapper.deleteById(id);
  }

  @Override
  public Page<FactorQuoteBaseMapping> pageMappings(
      Long factorIdentityId,
      String quoteFieldCode,
      String keyword,
      Boolean enabled,
      int page,
      int pageSize) {
    LambdaQueryWrapper<FactorQuoteBaseMapping> query =
        Wrappers.lambdaQuery(FactorQuoteBaseMapping.class);
    if (factorIdentityId != null) {
      query.eq(FactorQuoteBaseMapping::getFactorIdentityId, factorIdentityId);
    }
    if (StringUtils.hasText(quoteFieldCode)) {
      query.eq(FactorQuoteBaseMapping::getQuoteFieldCode, quoteFieldCode.trim());
    }
    if (enabled != null) {
      query.eq(FactorQuoteBaseMapping::getEnabled, enabled ? 1 : 0);
    }
    if (StringUtils.hasText(keyword)) {
      String like = keyword.trim();
      query.and(w -> w.like(FactorQuoteBaseMapping::getQuoteFieldName, like)
          .or().like(FactorQuoteBaseMapping::getVariableCode, like)
          .or().like(FactorQuoteBaseMapping::getMatchedKeyword, like)
          .or().like(FactorQuoteBaseMapping::getMatchSource, like));
    }
    query.orderByAsc(FactorQuoteBaseMapping::getFactorIdentityId)
        .orderByAsc(FactorQuoteBaseMapping::getQuoteFieldCode)
        .orderByDesc(FactorQuoteBaseMapping::getId);
    return mappingMapper.selectPage(new Page<>(page(page), size(pageSize)), query);
  }

  @Override
  public List<FactorQuoteBaseMapping> listMappingsByFactorIdentityId(Long factorIdentityId) {
    if (factorIdentityId == null) {
      return List.of();
    }
    return mappingMapper.selectList(Wrappers.lambdaQuery(FactorQuoteBaseMapping.class)
        .eq(FactorQuoteBaseMapping::getFactorIdentityId, factorIdentityId)
        .eq(FactorQuoteBaseMapping::getEnabled, 1)
        .orderByAsc(FactorQuoteBaseMapping::getQuoteFieldCode)
        .orderByDesc(FactorQuoteBaseMapping::getId));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBasePriceDetectResult detectAndSaveFactorQuoteBaseMapping(Long factorIdentityId) {
    if (factorIdentityId == null) {
      return QuoteBasePriceDetectResult.unrecognized(null, "factorIdentityId 为空");
    }
    FactorIdentity identity = factorIdentityMapper.selectById(factorIdentityId);
    if (identity == null) {
      return QuoteBasePriceDetectResult.unrecognized(factorIdentityId, "影响因素身份不存在");
    }
    String candidateText = candidateText(identity);
    if (!StringUtils.hasText(candidateText)) {
      return QuoteBasePriceDetectResult.unrecognized(factorIdentityId, "候选文本为空");
    }

    // 先按当前业务单元识别；没有命中时再回退到全局公共规则。
    List<RuleMatch> businessMatches = matchRules(
        enabledRulesByBusinessUnit(identity.getBusinessUnitType()), candidateText);
    List<RuleMatch> matches = businessMatches.isEmpty()
        ? matchRules(enabledRulesByBusinessUnit(""), candidateText)
        : businessMatches;
    if (matches.isEmpty()) {
      return QuoteBasePriceDetectResult.unrecognized(factorIdentityId, "未命中公共基价规则");
    }
    matches.sort(Comparator
        .comparingInt((RuleMatch match) -> safePriority(match.rule()))
        .thenComparing(match -> match.rule().getId() == null ? Long.MAX_VALUE : match.rule().getId()));
    int bestPriority = safePriority(matches.getFirst().rule());
    List<RuleMatch> bestMatches = matches.stream()
        .filter(match -> safePriority(match.rule()) == bestPriority)
        .toList();
    if (bestMatches.size() > 1) {
      // 同优先级多规则命中时不自动写映射，避免把影响因素错绑到 OA 基价字段。
      return QuoteBasePriceDetectResult.conflict(
          factorIdentityId,
          "同优先级命中多个公共基价规则：" + conflictRuleNames(bestMatches));
    }

    RuleMatch selected = bestMatches.getFirst();
    upsertDetectedMapping(identity, selected);
    QuoteBasePriceMappingRule rule = selected.rule();
    return QuoteBasePriceDetectResult.recognized(
        factorIdentityId,
        rule.getQuoteFieldCode(),
        rule.getQuoteFieldName(),
        rule.getVariableCode(),
        selected.keyword());
  }

  private LambdaQueryWrapper<QuoteBasePriceMappingRule> ruleQuery() {
    return Wrappers.lambdaQuery(QuoteBasePriceMappingRule.class);
  }

  private List<QuoteBasePriceMappingRule> enabledRulesByBusinessUnit(String businessUnitType) {
    return ruleMapper.selectList(ruleQuery()
        .eq(QuoteBasePriceMappingRule::getEnabled, 1)
        .eq(QuoteBasePriceMappingRule::getBusinessUnitType, normalizeBusinessUnit(businessUnitType))
        .orderByAsc(QuoteBasePriceMappingRule::getPriority)
        .orderByDesc(QuoteBasePriceMappingRule::getId));
  }

  private QuoteBasePriceMappingRule requireRule(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("规则 id 不能为空");
    }
    QuoteBasePriceMappingRule existing = ruleMapper.selectById(id);
    if (existing == null) {
      throw new IllegalArgumentException("规则不存在：id=" + id);
    }
    return existing;
  }

  private FactorQuoteBaseMapping requireMapping(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("识别结果 id 不能为空");
    }
    FactorQuoteBaseMapping existing = mappingMapper.selectById(id);
    if (existing == null) {
      throw new IllegalArgumentException("识别结果不存在：id=" + id);
    }
    return existing;
  }

  private void normalizeRule(
      QuoteBasePriceMappingRule rule, String operator, boolean creating) {
    rule.setBusinessUnitType(normalizeBusinessUnit(rule.getBusinessUnitType()));
    rule.setQuoteFieldCode(required(rule.getQuoteFieldCode(), "报价单字段编码"));
    if (!SUPPORTED_QUOTE_FIELDS.contains(rule.getQuoteFieldCode())) {
      throw new IllegalArgumentException("当前阶段不支持的报价单字段：" + rule.getQuoteFieldCode());
    }
    rule.setQuoteFieldName(required(rule.getQuoteFieldName(), "报价单字段名称"));
    rule.setVariableCode(required(rule.getVariableCode(), "变量编码"));
    rule.setMatchKeywordsJson(required(rule.getMatchKeywordsJson(), "匹配关键词"));
    ensureNonEmptyKeywords(rule.getMatchKeywordsJson());
    if (!StringUtils.hasText(rule.getMatchMode())) {
      rule.setMatchMode(DEFAULT_MATCH_MODE);
    } else {
      rule.setMatchMode(rule.getMatchMode().trim());
    }
    if (rule.getPriority() == null) {
      rule.setPriority(100);
    }
    if (rule.getEnabled() == null) {
      rule.setEnabled(1);
    }
    if (rule.getDeleted() == null) {
      rule.setDeleted(0);
    }
    LocalDateTime now = LocalDateTime.now();
    if (creating) {
      rule.setCreatedAt(now);
      if (StringUtils.hasText(operator)) {
        rule.setCreatedBy(operator.trim());
      }
    }
    rule.setUpdatedAt(now);
    if (StringUtils.hasText(operator)) {
      rule.setUpdatedBy(operator.trim());
    }
  }

  private void mergeRule(QuoteBasePriceMappingRule target, QuoteBasePriceMappingRule patch) {
    if (patch == null) {
      return;
    }
    if (patch.getBusinessUnitType() != null) {
      target.setBusinessUnitType(patch.getBusinessUnitType());
    }
    if (patch.getQuoteFieldCode() != null) {
      target.setQuoteFieldCode(patch.getQuoteFieldCode());
    }
    if (patch.getQuoteFieldName() != null) {
      target.setQuoteFieldName(patch.getQuoteFieldName());
    }
    if (patch.getVariableCode() != null) {
      target.setVariableCode(patch.getVariableCode());
    }
    if (patch.getMatchKeywordsJson() != null) {
      target.setMatchKeywordsJson(patch.getMatchKeywordsJson());
    }
    if (patch.getMatchMode() != null) {
      target.setMatchMode(patch.getMatchMode());
    }
    if (patch.getPriority() != null) {
      target.setPriority(patch.getPriority());
    }
    if (patch.getEnabled() != null) {
      target.setEnabled(patch.getEnabled());
    }
    if (patch.getRemark() != null) {
      target.setRemark(patch.getRemark());
    }
  }

  private void normalizeMapping(
      FactorQuoteBaseMapping mapping, String operator, boolean creating) {
    if (mapping.getFactorIdentityId() == null) {
      throw new IllegalArgumentException("影响因素身份 id 不能为空");
    }
    mapping.setQuoteFieldCode(required(mapping.getQuoteFieldCode(), "报价单字段编码"));
    if (!SUPPORTED_QUOTE_FIELDS.contains(mapping.getQuoteFieldCode())) {
      throw new IllegalArgumentException("当前阶段不支持的报价单字段：" + mapping.getQuoteFieldCode());
    }
    mapping.setQuoteFieldName(required(mapping.getQuoteFieldName(), "报价单字段名称"));
    mapping.setVariableCode(required(mapping.getVariableCode(), "变量编码"));
    if (!StringUtils.hasText(mapping.getMatchSource())) {
      mapping.setMatchSource(DEFAULT_MATCH_SOURCE);
    } else {
      mapping.setMatchSource(mapping.getMatchSource().trim());
    }
    if (!StringUtils.hasText(mapping.getConfidence())) {
      mapping.setConfidence(DEFAULT_CONFIDENCE);
    } else {
      mapping.setConfidence(mapping.getConfidence().trim());
    }
    if (mapping.getEnabled() == null) {
      mapping.setEnabled(1);
    }
    if (mapping.getDeleted() == null) {
      mapping.setDeleted(0);
    }
    LocalDateTime now = LocalDateTime.now();
    if (creating) {
      mapping.setCreatedAt(now);
      if (StringUtils.hasText(operator)) {
        mapping.setCreatedBy(operator.trim());
      }
    }
    mapping.setUpdatedAt(now);
    if (StringUtils.hasText(operator)) {
      mapping.setUpdatedBy(operator.trim());
    }
  }

  private void upsertDetectedMapping(FactorIdentity identity, RuleMatch selected) {
    QuoteBasePriceMappingRule rule = selected.rule();
    FactorQuoteBaseMapping mapping = mappingMapper.selectOne(
        Wrappers.lambdaQuery(FactorQuoteBaseMapping.class)
            .eq(FactorQuoteBaseMapping::getFactorIdentityId, identity.getId())
            .eq(FactorQuoteBaseMapping::getQuoteFieldCode, rule.getQuoteFieldCode())
            .last("LIMIT 1"));
    boolean creating = mapping == null;
    if (mapping == null) {
      mapping = new FactorQuoteBaseMapping();
      mapping.setFactorIdentityId(identity.getId());
      mapping.setQuoteFieldCode(rule.getQuoteFieldCode());
    }
    mapping.setRuleId(rule.getId());
    mapping.setQuoteFieldName(rule.getQuoteFieldName());
    mapping.setVariableCode(rule.getVariableCode());
    mapping.setMatchedKeyword(selected.keyword());
    mapping.setMatchSource(DEFAULT_MATCH_SOURCE);
    mapping.setConfidence(DEFAULT_CONFIDENCE);
    mapping.setEnabled(1);
    normalizeMapping(mapping, "system", creating);
    if (creating) {
      mappingMapper.insert(mapping);
    } else {
      mappingMapper.updateById(mapping);
    }
  }

  private List<RuleMatch> matchRules(List<QuoteBasePriceMappingRule> rules, String candidateText) {
    if (rules == null || rules.isEmpty()) {
      return List.of();
    }
    String normalizedCandidate = normalizeForMatch(candidateText);
    List<RuleMatch> matches = new ArrayList<>();
    for (QuoteBasePriceMappingRule rule : rules) {
      for (String keyword : keywords(rule)) {
        if (StringUtils.hasText(keyword)
            && normalizedCandidate.contains(normalizeForMatch(keyword))) {
          matches.add(new RuleMatch(rule, keyword.trim()));
          break;
        }
      }
    }
    return matches;
  }

  private List<String> keywords(QuoteBasePriceMappingRule rule) {
    if (rule == null || !StringUtils.hasText(rule.getMatchKeywordsJson())) {
      return List.of();
    }
    try {
      return objectMapper.readValue(rule.getMatchKeywordsJson(), STRING_LIST_TYPE);
    } catch (Exception ex) {
      return List.of();
    }
  }

  private void ensureNonEmptyKeywords(String matchKeywordsJson) {
    List<String> values;
    try {
      values = objectMapper.readValue(matchKeywordsJson, STRING_LIST_TYPE);
    } catch (Exception ex) {
      throw new IllegalArgumentException("匹配关键词必须是 JSON 数组");
    }
    boolean hasKeyword = values != null && values.stream().anyMatch(StringUtils::hasText);
    if (!hasKeyword) {
      throw new IllegalArgumentException("匹配关键词不能为空");
    }
  }

  private String candidateText(FactorIdentity identity) {
    return normalize(identity.getFactorName())
        + " " + normalize(identity.getShortName())
        + " " + normalize(identity.getPriceSource());
  }

  private String normalizeForMatch(String value) {
    return normalize(value).toLowerCase();
  }

  private int safePriority(QuoteBasePriceMappingRule rule) {
    return rule == null || rule.getPriority() == null ? 100 : rule.getPriority();
  }

  private String conflictRuleNames(List<RuleMatch> matches) {
    return matches.stream()
        .map(match -> match.rule().getQuoteFieldCode() + "/" + match.rule().getVariableCode())
        .distinct()
        .toList()
        .toString();
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private void touch(QuoteBasePriceMappingRule rule, String operator) {
    rule.setUpdatedAt(LocalDateTime.now());
    if (StringUtils.hasText(operator)) {
      rule.setUpdatedBy(operator.trim());
    }
  }

  private void touch(FactorQuoteBaseMapping mapping, String operator) {
    mapping.setUpdatedAt(LocalDateTime.now());
    if (StringUtils.hasText(operator)) {
      mapping.setUpdatedBy(operator.trim());
    }
  }

  private String normalizeBusinessUnit(String businessUnitType) {
    return StringUtils.hasText(businessUnitType) ? businessUnitType.trim() : "";
  }

  private String required(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(fieldName + "不能为空");
    }
    return value.trim();
  }

  private int page(int page) {
    return page <= 0 ? 1 : page;
  }

  private int size(int pageSize) {
    if (pageSize <= 0) {
      return 20;
    }
    return Math.min(pageSize, 200);
  }

  private record RuleMatch(QuoteBasePriceMappingRule rule, String keyword) {}
}
