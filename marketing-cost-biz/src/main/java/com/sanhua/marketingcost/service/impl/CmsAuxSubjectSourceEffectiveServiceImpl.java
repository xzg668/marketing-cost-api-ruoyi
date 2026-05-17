package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.dto.PlanEligibility;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsPlanEligibilityService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CmsAuxSubjectSourceEffectiveServiceImpl implements CmsAuxSubjectSourceEffectiveService {
  public static final String AUX_SUBJECT = "AUX_SUBJECT";
  private static final String FIRST_SUBJECT_AUX_MATERIAL = "辅助材料";
  private static final String EXCLUDED_AUX_SUBJECT_PACKAGING = "包装辅料";
  private static final String SUBJECT_TABLE = "cms_product_subject_cost_raw";
  private static final BigDecimal CENT_TO_YUAN = new BigDecimal("100");

  private final CmsProductSubjectCostRawMapper productSubjectCostRawMapper;
  private final CmsSubjectSettingRawMapper subjectSettingRawMapper;
  private final CmsCostSourceEffectiveMapper effectiveMapper;
  private final CmsCostSourceEffectiveLogMapper logMapper;
  private final CmsPlanEligibilityService planEligibilityService;

  public CmsAuxSubjectSourceEffectiveServiceImpl(
      CmsProductSubjectCostRawMapper productSubjectCostRawMapper,
      CmsSubjectSettingRawMapper subjectSettingRawMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      CmsCostSourceEffectiveLogMapper logMapper,
      CmsPlanEligibilityService planEligibilityService) {
    this.productSubjectCostRawMapper = productSubjectCostRawMapper;
    this.subjectSettingRawMapper = subjectSettingRawMapper;
    this.effectiveMapper = effectiveMapper;
    this.logMapper = logMapper;
    this.planEligibilityService = planEligibilityService;
  }

  @Override
  @Transactional
  public CmsEffectiveSourceGenerateResponse generateDefaultSources(
      int costYear, String operator, String businessUnitType) {
    return applyDefaultSources(costYear, operator, businessUnitType, true);
  }

  @Override
  @Transactional
  public CmsEffectiveSourceGenerateResponse ensureDefaultSources(
      int costYear, String operator, String businessUnitType) {
    return applyDefaultSources(costYear, operator, businessUnitType, false);
  }

  private CmsEffectiveSourceGenerateResponse applyDefaultSources(
      int costYear, String operator, String businessUnitType, boolean updateExistingDefault) {
    CmsEffectiveSourceGenerateResponse response = new CmsEffectiveSourceGenerateResponse();
    response.setCostYear(costYear);
    Map<String, List<Aggregate>> byParentSubject = new LinkedHashMap<>();
    for (Aggregate aggregate : aggregateAux(costYear, businessUnitType).values()) {
      byParentSubject.computeIfAbsent(aggregate.parentCode + "|" + aggregate.subjectCode, ignored -> new ArrayList<>()).add(aggregate);
    }
    for (List<Aggregate> candidates : byParentSubject.values()) {
      candidates.sort(Comparator.comparing((Aggregate aggregate) -> preferredPeriodOrder(costYear, aggregate.period))
          .thenComparing(aggregate -> aggregate.period));
      Aggregate selected = null;
      PlanEligibility selectedEligibility = null;
      for (Aggregate candidate : candidates) {
        PlanEligibility eligibility = eligibility(candidate.parentCode, candidate.period, businessUnitType);
        if (eligibility.isAuxSubjectAllowed()) {
          selected = candidate;
          selectedEligibility = eligibility;
          break;
        }
        response.setBlockedCount(response.getBlockedCount() + 1);
        insertLog(null, costYear, candidate, null, "BLOCKED", eligibility.getReason(), operator, businessUnitType);
      }
      if (selected == null) {
        continue;
      }
      CmsCostSourceEffective existing =
          findExisting(costYear, selected.parentCode, selected.subjectCode, businessUnitType);
      if (existing != null
          && (Integer.valueOf(0).equals(existing.getDefaultFlag()) || !updateExistingDefault)) {
        response.setSkippedCount(response.getSkippedCount() + 1);
        continue;
      }
      CmsCostSourceEffective oldSnapshot = copyEffective(existing);
      CmsCostSourceEffective saved =
          upsert(existing, costYear, selected, 1, selectedEligibility.getReason(), operator, businessUnitType);
      if (existing == null) {
        response.setInsertedCount(response.getInsertedCount() + 1);
      } else {
        response.setUpdatedCount(response.getUpdatedCount() + 1);
      }
      insertLog(saved.getId(), costYear, selected, oldSnapshot, "DEFAULT", selectedEligibility.getReason(), operator, businessUnitType);
    }
    return response;
  }

  @Override
  @Transactional
  public CmsCostSourceEffective refreshSource(
      CmsEffectiveSourceRefreshRequest request, String operator, String businessUnitType) {
    validateRefreshRequest(request);
    Aggregate aggregate =
        aggregateAux(request.getCostYear(), businessUnitType)
            .get(key(request.getParentCode(), request.getNewPeriod(), request.getSubjectCode()));
    if (aggregate == null) {
      throw new IllegalArgumentException("未找到该料号在新期间的 CMS 辅料来源");
    }
    PlanEligibility eligibility = eligibility(request.getParentCode(), request.getNewPeriod(), businessUnitType);
    if (!eligibility.isAuxSubjectAllowed()) {
      insertLog(null, request.getCostYear(), aggregate, null, "BLOCKED", eligibility.getReason(), operator, businessUnitType);
      throw new IllegalArgumentException(eligibility.getReason());
    }
    CmsCostSourceEffective existing =
        findExisting(request.getCostYear(), aggregate.parentCode, aggregate.subjectCode, businessUnitType);
    CmsCostSourceEffective oldSnapshot = copyEffective(existing);
    CmsCostSourceEffective saved =
        upsert(existing, request.getCostYear(), aggregate, 0, request.getRefreshReason(), operator, businessUnitType);
    insertLog(saved.getId(), request.getCostYear(), aggregate, oldSnapshot, "REFRESH", request.getRefreshReason(), operator, businessUnitType);
    return saved;
  }

  @Override
  @Transactional
  public CmsEffectiveSourceGenerateResponse refreshParentPeriod(
      CmsEffectiveSourceRefreshRequest request, String operator, String businessUnitType) {
    validateParentPeriodRefreshRequest(request);
    CmsEffectiveSourceGenerateResponse response = new CmsEffectiveSourceGenerateResponse();
    response.setCostYear(request.getCostYear());
    PlanEligibility eligibility = eligibility(request.getParentCode(), request.getNewPeriod(), businessUnitType);
    Map<String, Aggregate> aggregates = aggregateAux(request.getCostYear(), businessUnitType);
    boolean matched = false;
    for (Aggregate aggregate : aggregates.values()) {
      if (!request.getParentCode().equals(aggregate.parentCode)
          || !request.getNewPeriod().equals(aggregate.period)) {
        continue;
      }
      matched = true;
      if (!eligibility.isAuxSubjectAllowed()) {
        response.setBlockedCount(response.getBlockedCount() + 1);
        insertLog(null, request.getCostYear(), aggregate, null, "BLOCKED", eligibility.getReason(), operator, businessUnitType);
        continue;
      }
      CmsCostSourceEffective existing =
          findExisting(request.getCostYear(), aggregate.parentCode, aggregate.subjectCode, businessUnitType);
      CmsCostSourceEffective oldSnapshot = copyEffective(existing);
      CmsCostSourceEffective saved =
          upsert(existing, request.getCostYear(), aggregate, 0, request.getRefreshReason(), operator, businessUnitType);
      if (existing == null) {
        response.setInsertedCount(response.getInsertedCount() + 1);
      } else {
        response.setUpdatedCount(response.getUpdatedCount() + 1);
      }
      insertLog(saved.getId(), request.getCostYear(), aggregate, oldSnapshot, "REFRESH", request.getRefreshReason(), operator, businessUnitType);
    }
    if (!matched) {
      response.setSkippedCount(response.getSkippedCount() + 1);
    }
    return response;
  }

  private Map<String, Aggregate> aggregateAux(int costYear, String businessUnitType) {
    Map<String, SubjectDefinition> subjectDefinitions = loadAuxSubjectDefinitions(businessUnitType);
    if (subjectDefinitions.isEmpty()) {
      return new LinkedHashMap<>();
    }
    List<CmsProductSubjectCostRaw> rows =
        productSubjectCostRawMapper.selectList(
            new QueryWrapper<CmsProductSubjectCostRaw>()
                .likeRight("period", costYear + "-")
                .in("second_subject_code", subjectDefinitions.keySet())
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType));
    Map<String, Aggregate> result = new LinkedHashMap<>();
    for (CmsProductSubjectCostRaw row : rows) {
      String parentCode = trim(row.getParentCode());
      String period = trim(row.getPeriod());
      String subjectCode = trim(row.getSecondSubjectCode());
      SubjectDefinition subjectDefinition = subjectDefinitions.get(subjectCode);
      if (!StringUtils.hasText(parentCode)
          || !StringUtils.hasText(period)
          || !StringUtils.hasText(subjectCode)
          || subjectDefinition == null) {
        continue;
      }
      Aggregate aggregate =
          result.computeIfAbsent(
              key(parentCode, period, subjectCode),
              ignored ->
                  new Aggregate(
                      parentCode,
                      period,
                      subjectCode,
                      subjectDefinition.subjectName));
      aggregate.amount = aggregate.amount.add(toYuan(row.getMaterialPrice()));
      aggregate.rowIds.add(row.getId());
    }
    return result;
  }

  private Map<String, SubjectDefinition> loadAuxSubjectDefinitions(String businessUnitType) {
    List<CmsSubjectSettingRaw> settings =
        subjectSettingRawMapper.selectList(
            new QueryWrapper<CmsSubjectSettingRaw>()
                .eq("first_subject_name", FIRST_SUBJECT_AUX_MATERIAL)
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType)
                .orderByAsc("second_subject_code")
                .orderByAsc("id"));
    Map<String, SubjectDefinition> result = new LinkedHashMap<>();
    for (CmsSubjectSettingRaw setting : settings) {
      String secondSubjectCode = trim(setting.getSecondSubjectCode());
      String secondSubjectName = trim(setting.getSecondSubjectName());
      if (StringUtils.hasText(secondSubjectCode)
          && StringUtils.hasText(secondSubjectName)
          && !EXCLUDED_AUX_SUBJECT_PACKAGING.equals(secondSubjectName)) {
        result.putIfAbsent(
            secondSubjectCode, new SubjectDefinition(secondSubjectCode, secondSubjectName));
      }
    }
    return result;
  }

  private CmsCostSourceEffective upsert(
      CmsCostSourceEffective existing,
      int costYear,
      Aggregate aggregate,
      int defaultFlag,
      String reason,
      String operator,
      String businessUnitType) {
    CmsCostSourceEffective effective = existing == null ? new CmsCostSourceEffective() : existing;
    effective.setCostYear(costYear);
    effective.setSourceType(AUX_SUBJECT);
    effective.setParentCode(aggregate.parentCode);
    effective.setPeriod(aggregate.period);
    effective.setSubjectCode(aggregate.subjectCode);
    effective.setSubjectName(aggregate.subjectName);
    effective.setSourceTable(SUBJECT_TABLE);
    effective.setSourceRowIds(joinIds(aggregate.rowIds));
    effective.setAmountYuan(aggregate.amount.setScale(6, RoundingMode.HALF_UP));
    effective.setDefaultFlag(defaultFlag);
    effective.setRefreshReason(reason);
    effective.setConfirmedBy(operator);
    effective.setBusinessUnitType(normalizeBusinessUnit(businessUnitType));
    if (existing == null) {
      effectiveMapper.insert(effective);
    } else {
      effectiveMapper.updateById(effective);
    }
    return effective;
  }

  private CmsCostSourceEffective findExisting(
      int costYear, String parentCode, String subjectCode, String businessUnitType) {
    return effectiveMapper.selectOne(
        new QueryWrapper<CmsCostSourceEffective>()
            .eq("cost_year", costYear)
            .eq("source_type", AUX_SUBJECT)
            .eq("parent_code", parentCode)
            .eq("subject_code", subjectCode)
            .eq("business_unit_type", normalizeBusinessUnit(businessUnitType))
            .last("LIMIT 1"));
  }

  private PlanEligibility eligibility(String parentCode, String period, String businessUnitType) {
    return planEligibilityService
        .checkEligibility(List.of(parentCode), List.of(period), normalizeBusinessUnit(businessUnitType))
        .getOrDefault(parentCode + "|" + period, PlanEligibility.allowed(parentCode, period, "允许生效"));
  }

  private void insertLog(
      Long effectiveSourceId,
      int costYear,
      Aggregate aggregate,
      CmsCostSourceEffective existing,
      String actionType,
      String message,
      String operator,
      String businessUnitType) {
    CmsCostSourceEffectiveLog log = new CmsCostSourceEffectiveLog();
    log.setEffectiveSourceId(effectiveSourceId);
    log.setCostYear(costYear);
    log.setSourceType(AUX_SUBJECT);
    log.setParentCode(aggregate.parentCode);
    log.setOldPeriod(existing == null ? null : existing.getPeriod());
    log.setNewPeriod(aggregate.period);
    log.setSubjectCode(aggregate.subjectCode);
    log.setSubjectName(aggregate.subjectName);
    log.setOldAmountYuan(existing == null ? null : existing.getAmountYuan());
    log.setNewAmountYuan(aggregate.amount.setScale(6, RoundingMode.HALF_UP));
    log.setActionType(actionType);
    log.setMessage(message);
    log.setOperator(operator);
    log.setBusinessUnitType(normalizeBusinessUnit(businessUnitType));
    logMapper.insert(log);
  }

  private void validateRefreshRequest(CmsEffectiveSourceRefreshRequest request) {
    validateParentPeriodRefreshRequest(request);
    if (!AUX_SUBJECT.equals(request.getSourceType())) {
      throw new IllegalArgumentException("辅料来源类型必须为 AUX_SUBJECT");
    }
    if (!StringUtils.hasText(request.getSubjectCode())) {
      throw new IllegalArgumentException("subjectCode 不能为空");
    }
  }

  private void validateParentPeriodRefreshRequest(CmsEffectiveSourceRefreshRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("刷新请求不能为空");
    }
    if (request.getCostYear() == null) {
      throw new IllegalArgumentException("costYear 不能为空");
    }
    if (!StringUtils.hasText(request.getParentCode())) {
      throw new IllegalArgumentException("parentCode 不能为空");
    }
    if (!StringUtils.hasText(request.getNewPeriod())) {
      throw new IllegalArgumentException("newPeriod 不能为空");
    }
    if (!request.getNewPeriod().startsWith(request.getCostYear() + "-")) {
      throw new IllegalArgumentException("newPeriod 必须属于 costYear");
    }
  }

  private int preferredPeriodOrder(int costYear, String period) {
    return (costYear + "-01").equals(period) ? 0 : 1;
  }

  private BigDecimal toYuan(BigDecimal cent) {
    if (cent == null) {
      return BigDecimal.ZERO;
    }
    return cent.divide(CENT_TO_YUAN, 6, RoundingMode.HALF_UP);
  }

  private String key(String parentCode, String period, String subjectCode) {
    return parentCode + "|" + period + "|" + subjectCode;
  }

  private String trim(String value) {
    return value == null ? null : value.trim();
  }

  private String joinIds(Collection<Long> ids) {
    return ids.stream().filter(id -> id != null).map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
  }

  private String normalizeBusinessUnit(String businessUnitType) {
    return businessUnitType == null ? "" : businessUnitType;
  }

  private CmsCostSourceEffective copyEffective(CmsCostSourceEffective source) {
    if (source == null) {
      return null;
    }
    CmsCostSourceEffective copy = new CmsCostSourceEffective();
    copy.setId(source.getId());
    copy.setPeriod(source.getPeriod());
    copy.setAmountYuan(source.getAmountYuan());
    return copy;
  }

  private static final class Aggregate {
    private final String parentCode;
    private final String period;
    private final String subjectCode;
    private final String subjectName;
    private final List<Long> rowIds = new ArrayList<>();
    private BigDecimal amount = BigDecimal.ZERO;

    private Aggregate(String parentCode, String period, String subjectCode, String subjectName) {
      this.parentCode = parentCode;
      this.period = period;
      this.subjectCode = subjectCode;
      this.subjectName = subjectName;
    }
  }

  private static final class SubjectDefinition {
    private final String subjectCode;
    private final String subjectName;

    private SubjectDefinition(String subjectCode, String subjectName) {
      this.subjectCode = subjectCode;
      this.subjectName = subjectName;
    }
  }
}
