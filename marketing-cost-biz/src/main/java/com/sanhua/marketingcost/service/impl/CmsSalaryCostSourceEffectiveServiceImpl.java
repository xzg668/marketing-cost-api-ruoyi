package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.dto.PlanEligibility;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.service.CmsPlanEligibilityService;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
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
public class CmsSalaryCostSourceEffectiveServiceImpl implements CmsSalaryCostSourceEffectiveService {
  public static final String SALARY_DIRECT = "SALARY_DIRECT";
  public static final String SALARY_INDIRECT = "SALARY_INDIRECT";
  private static final String DIRECT_LABOR_SUBJECT_CODE = "0301";
  private static final String DIRECT_LABOR_SUBJECT_NAME = "直接人工工资";
  private static final String FIRST_SUBJECT_SALARY = "工资";
  private static final String INDIRECT_LABOR_SUBJECT_NAME = "辅助人员工资";
  private static final String WORKSHOP_TABLE = "cms_workshop_labor_raw";
  private static final String SUBJECT_TABLE = "cms_product_subject_cost_raw";
  private static final BigDecimal CENT_TO_YUAN = new BigDecimal("100");

  private final CmsWorkshopLaborRawMapper workshopLaborRawMapper;
  private final CmsProductSubjectCostRawMapper productSubjectCostRawMapper;
  private final CmsSubjectSettingRawMapper subjectSettingRawMapper;
  private final CmsCostSourceEffectiveMapper effectiveMapper;
  private final CmsCostSourceEffectiveLogMapper logMapper;
  private final CmsPlanEligibilityService planEligibilityService;

  public CmsSalaryCostSourceEffectiveServiceImpl(
      CmsWorkshopLaborRawMapper workshopLaborRawMapper,
      CmsProductSubjectCostRawMapper productSubjectCostRawMapper,
      CmsSubjectSettingRawMapper subjectSettingRawMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      CmsCostSourceEffectiveLogMapper logMapper,
      CmsPlanEligibilityService planEligibilityService) {
    this.workshopLaborRawMapper = workshopLaborRawMapper;
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
    CmsEffectiveSourceGenerateResponse response = new CmsEffectiveSourceGenerateResponse();
    response.setCostYear(costYear);
    applyAggregates(response, costYear, SALARY_DIRECT, aggregateDirect(costYear, businessUnitType), operator, businessUnitType, true);
    applyAggregates(response, costYear, SALARY_INDIRECT, aggregateIndirect(costYear, businessUnitType), operator, businessUnitType, true);
    return response;
  }

  @Override
  @Transactional
  public CmsEffectiveSourceGenerateResponse ensureDefaultSources(
      int costYear, String operator, String businessUnitType) {
    CmsEffectiveSourceGenerateResponse response = new CmsEffectiveSourceGenerateResponse();
    response.setCostYear(costYear);
    applyAggregates(response, costYear, SALARY_DIRECT, aggregateDirect(costYear, businessUnitType), operator, businessUnitType, false);
    applyAggregates(response, costYear, SALARY_INDIRECT, aggregateIndirect(costYear, businessUnitType), operator, businessUnitType, false);
    return response;
  }

  @Override
  @Transactional
  public CmsCostSourceEffective refreshSource(
      CmsEffectiveSourceRefreshRequest request, String operator, String businessUnitType) {
    validateRefreshRequest(request);
    if (!SALARY_DIRECT.equals(request.getSourceType()) && !SALARY_INDIRECT.equals(request.getSourceType())) {
      throw new IllegalArgumentException("工资来源类型只支持 SALARY_DIRECT / SALARY_INDIRECT");
    }
    Aggregate aggregate =
        SALARY_DIRECT.equals(request.getSourceType())
            ? aggregateDirect(request.getCostYear(), businessUnitType).get(key(request.getParentCode(), request.getNewPeriod()))
            : aggregateIndirect(request.getCostYear(), businessUnitType).get(key(request.getParentCode(), request.getNewPeriod()));
    if (aggregate == null) {
      throw new IllegalArgumentException("未找到该料号在新期间的 CMS 工资来源");
    }
    PlanEligibility eligibility = eligibility(request.getParentCode(), request.getNewPeriod(), businessUnitType);
    boolean allowed =
        SALARY_DIRECT.equals(request.getSourceType())
            ? eligibility.isDirectLaborAllowed()
            : eligibility.isIndirectLaborAllowed();
    if (!allowed) {
      insertLog(null, request.getCostYear(), request.getSourceType(), aggregate, null, "BLOCKED", eligibility.getReason(), operator, businessUnitType);
      throw new IllegalArgumentException(eligibility.getReason());
    }
    CmsCostSourceEffective existing =
        findExisting(request.getCostYear(), request.getSourceType(), request.getParentCode(), businessUnitType);
    CmsCostSourceEffective oldSnapshot = copyEffective(existing);
    CmsCostSourceEffective saved =
        upsert(existing, request.getCostYear(), request.getSourceType(), aggregate, 0, request.getRefreshReason(), operator, businessUnitType);
    insertLog(saved.getId(), request.getCostYear(), request.getSourceType(), aggregate, oldSnapshot, "REFRESH", request.getRefreshReason(), operator, businessUnitType);
    return saved;
  }

  @Override
  @Transactional
  public CmsEffectiveSourceGenerateResponse refreshParentPeriod(
      CmsEffectiveSourceRefreshRequest request, String operator, String businessUnitType) {
    validateParentPeriodRefreshRequest(request);
    CmsEffectiveSourceGenerateResponse response = new CmsEffectiveSourceGenerateResponse();
    response.setCostYear(request.getCostYear());
    refreshSalaryType(
        response,
        request,
        SALARY_DIRECT,
        aggregateDirect(request.getCostYear(), businessUnitType).get(key(request.getParentCode(), request.getNewPeriod())),
        operator,
        businessUnitType);
    refreshSalaryType(
        response,
        request,
        SALARY_INDIRECT,
        aggregateIndirect(request.getCostYear(), businessUnitType).get(key(request.getParentCode(), request.getNewPeriod())),
        operator,
        businessUnitType);
    return response;
  }

  private void refreshSalaryType(
      CmsEffectiveSourceGenerateResponse response,
      CmsEffectiveSourceRefreshRequest request,
      String sourceType,
      Aggregate aggregate,
      String operator,
      String businessUnitType) {
    if (aggregate == null) {
      response.setSkippedCount(response.getSkippedCount() + 1);
      return;
    }
    PlanEligibility eligibility = eligibility(request.getParentCode(), request.getNewPeriod(), businessUnitType);
    boolean allowed =
        SALARY_DIRECT.equals(sourceType)
            ? eligibility.isDirectLaborAllowed()
            : eligibility.isIndirectLaborAllowed();
    if (!allowed) {
      response.setBlockedCount(response.getBlockedCount() + 1);
      insertLog(null, request.getCostYear(), sourceType, aggregate, null, "BLOCKED", eligibility.getReason(), operator, businessUnitType);
      return;
    }
    CmsCostSourceEffective existing =
        findExisting(request.getCostYear(), sourceType, request.getParentCode(), businessUnitType);
    CmsCostSourceEffective oldSnapshot = copyEffective(existing);
    CmsCostSourceEffective saved =
        upsert(existing, request.getCostYear(), sourceType, aggregate, 0, request.getRefreshReason(), operator, businessUnitType);
    if (existing == null) {
      response.setInsertedCount(response.getInsertedCount() + 1);
    } else {
      response.setUpdatedCount(response.getUpdatedCount() + 1);
    }
    insertLog(saved.getId(), request.getCostYear(), sourceType, aggregate, oldSnapshot, "REFRESH", request.getRefreshReason(), operator, businessUnitType);
  }

  private void applyAggregates(
      CmsEffectiveSourceGenerateResponse response,
      int costYear,
      String sourceType,
      Map<String, Aggregate> aggregates,
      String operator,
      String businessUnitType,
      boolean updateExistingDefault) {
    Map<String, List<Aggregate>> byParent = new LinkedHashMap<>();
    for (Aggregate aggregate : aggregates.values()) {
      byParent.computeIfAbsent(aggregate.parentCode, ignored -> new ArrayList<>()).add(aggregate);
    }
    for (Map.Entry<String, List<Aggregate>> entry : byParent.entrySet()) {
      List<Aggregate> candidates = entry.getValue();
      candidates.sort(Comparator.comparing((Aggregate aggregate) -> preferredPeriodOrder(costYear, aggregate.period))
          .thenComparing(aggregate -> aggregate.period));
      Aggregate selected = null;
      PlanEligibility selectedEligibility = null;
      for (Aggregate candidate : candidates) {
        PlanEligibility eligibility = eligibility(candidate.parentCode, candidate.period, businessUnitType);
        boolean allowed = SALARY_DIRECT.equals(sourceType) ? eligibility.isDirectLaborAllowed() : eligibility.isIndirectLaborAllowed();
        if (allowed) {
          selected = candidate;
          selectedEligibility = eligibility;
          break;
        }
        response.setBlockedCount(response.getBlockedCount() + 1);
        insertLog(null, costYear, sourceType, candidate, null, "BLOCKED", eligibility.getReason(), operator, businessUnitType);
      }
      if (selected == null) {
        continue;
      }
      CmsCostSourceEffective existing =
          findExisting(costYear, sourceType, selected.parentCode, businessUnitType);
      if (existing != null
          && (Integer.valueOf(0).equals(existing.getDefaultFlag()) || !updateExistingDefault)) {
        response.setSkippedCount(response.getSkippedCount() + 1);
        continue;
      }
      CmsCostSourceEffective oldSnapshot = copyEffective(existing);
      CmsCostSourceEffective saved =
          upsert(existing, costYear, sourceType, selected, 1, selectedEligibility.getReason(), operator, businessUnitType);
      if (existing == null) {
        response.setInsertedCount(response.getInsertedCount() + 1);
      } else {
        response.setUpdatedCount(response.getUpdatedCount() + 1);
      }
      insertLog(saved.getId(), costYear, sourceType, selected, oldSnapshot, "DEFAULT", selectedEligibility.getReason(), operator, businessUnitType);
    }
  }

  private Map<String, Aggregate> aggregateDirect(int costYear, String businessUnitType) {
    List<CmsWorkshopLaborRaw> rows =
        workshopLaborRawMapper.selectList(
            new QueryWrapper<CmsWorkshopLaborRaw>()
                .likeRight("period", costYear + "-")
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType));
    Map<String, Aggregate> result = new LinkedHashMap<>();
    for (CmsWorkshopLaborRaw row : rows) {
      if (!StringUtils.hasText(row.getParentCode()) || !StringUtils.hasText(row.getPeriod())) {
        continue;
      }
      Aggregate aggregate =
          result.computeIfAbsent(key(row.getParentCode(), row.getPeriod()), ignored -> new Aggregate(row.getParentCode(), row.getPeriod()));
      aggregate.amount = aggregate.amount.add(toYuan(row.getWorkingCostCent()));
      aggregate.rowIds.add(row.getId());
      aggregate.fillSubject(DIRECT_LABOR_SUBJECT_CODE, DIRECT_LABOR_SUBJECT_NAME);
      aggregate.fillMeta(row.getParentName(), row.getParentSpec(), row.getParentType(), row.getFirstUnitName());
    }
    return result;
  }

  private Map<String, Aggregate> aggregateIndirect(int costYear, String businessUnitType) {
    SubjectDefinition indirectSubject = loadIndirectLaborSubject(businessUnitType);
    if (indirectSubject == null) {
      return new LinkedHashMap<>();
    }
    List<CmsProductSubjectCostRaw> rows =
        productSubjectCostRawMapper.selectList(
            new QueryWrapper<CmsProductSubjectCostRaw>()
                .likeRight("period", costYear + "-")
                .eq("second_subject_code", indirectSubject.subjectCode)
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType));
    Map<String, Aggregate> result = new LinkedHashMap<>();
    for (CmsProductSubjectCostRaw row : rows) {
      String secondSubjectCode = trim(row.getSecondSubjectCode());
      if (!StringUtils.hasText(row.getParentCode())
          || !StringUtils.hasText(row.getPeriod())
          || !indirectSubject.subjectCode.equals(secondSubjectCode)) {
        continue;
      }
      Aggregate aggregate =
          result.computeIfAbsent(key(row.getParentCode(), row.getPeriod()), ignored -> new Aggregate(row.getParentCode(), row.getPeriod()));
      aggregate.amount = aggregate.amount.add(toYuan(row.getMaterialPrice()));
      aggregate.rowIds.add(row.getId());
      aggregate.fillSubject(indirectSubject.subjectCode, indirectSubject.subjectName);
      aggregate.fillMeta(row.getParentName(), row.getParentSpec(), row.getParentType(), row.getFirstUnitName());
    }
    return result;
  }

  private SubjectDefinition loadIndirectLaborSubject(String businessUnitType) {
    List<CmsSubjectSettingRaw> settings =
        subjectSettingRawMapper.selectList(
            new QueryWrapper<CmsSubjectSettingRaw>()
                .eq("first_subject_name", FIRST_SUBJECT_SALARY)
                .eq("second_subject_name", INDIRECT_LABOR_SUBJECT_NAME)
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType)
                .orderByAsc("id"));
    for (CmsSubjectSettingRaw setting : settings) {
      String secondSubjectCode = trim(setting.getSecondSubjectCode());
      String secondSubjectName = trim(setting.getSecondSubjectName());
      if (StringUtils.hasText(secondSubjectCode) && StringUtils.hasText(secondSubjectName)) {
        return new SubjectDefinition(secondSubjectCode, secondSubjectName);
      }
    }
    return null;
  }

  private CmsCostSourceEffective upsert(
      CmsCostSourceEffective existing,
      int costYear,
      String sourceType,
      Aggregate aggregate,
      int defaultFlag,
      String reason,
      String operator,
      String businessUnitType) {
    CmsCostSourceEffective effective = existing == null ? new CmsCostSourceEffective() : existing;
    effective.setCostYear(costYear);
    effective.setSourceType(sourceType);
    effective.setParentCode(aggregate.parentCode);
    effective.setPeriod(aggregate.period);
    effective.setSubjectCode(subjectCode(sourceType, aggregate));
    effective.setSubjectName(subjectName(sourceType, aggregate));
    effective.setSourceTable(SALARY_DIRECT.equals(sourceType) ? WORKSHOP_TABLE : SUBJECT_TABLE);
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
      int costYear, String sourceType, String parentCode, String businessUnitType) {
    return effectiveMapper.selectOne(
        new QueryWrapper<CmsCostSourceEffective>()
            .eq("cost_year", costYear)
            .eq("source_type", sourceType)
            .eq("parent_code", parentCode)
            .eq("business_unit_type", normalizeBusinessUnit(businessUnitType))
            .last("LIMIT 1"));
  }

  private PlanEligibility eligibility(String parentCode, String period, String businessUnitType) {
    return planEligibilityService
        .checkEligibility(List.of(parentCode), List.of(period), normalizeBusinessUnit(businessUnitType))
        .getOrDefault(key(parentCode, period), PlanEligibility.allowed(parentCode, period, "允许生效"));
  }

  private void insertLog(
      Long effectiveSourceId,
      int costYear,
      String sourceType,
      Aggregate aggregate,
      CmsCostSourceEffective existing,
      String actionType,
      String message,
      String operator,
      String businessUnitType) {
    CmsCostSourceEffectiveLog log = new CmsCostSourceEffectiveLog();
    log.setEffectiveSourceId(effectiveSourceId);
    log.setCostYear(costYear);
    log.setSourceType(sourceType);
    log.setParentCode(aggregate.parentCode);
    log.setOldPeriod(existing == null ? null : existing.getPeriod());
    log.setNewPeriod(aggregate.period);
    log.setSubjectCode(subjectCode(sourceType, aggregate));
    log.setSubjectName(subjectName(sourceType, aggregate));
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
    if (!StringUtils.hasText(request.getSourceType())) {
      throw new IllegalArgumentException("sourceType 不能为空");
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

  private String key(String parentCode, String period) {
    return parentCode + "|" + period;
  }

  private String trim(String value) {
    return value == null ? null : value.trim();
  }

  private String joinIds(Collection<Long> ids) {
    return ids.stream().filter(id -> id != null).map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
  }

  private String subjectCode(String sourceType, Aggregate aggregate) {
    if (SALARY_DIRECT.equals(sourceType)) {
      return DIRECT_LABOR_SUBJECT_CODE;
    }
    return aggregate == null || aggregate.subjectCode == null ? "" : aggregate.subjectCode;
  }

  private String subjectName(String sourceType, Aggregate aggregate) {
    if (SALARY_DIRECT.equals(sourceType)) {
      return DIRECT_LABOR_SUBJECT_NAME;
    }
    return aggregate == null ? null : aggregate.subjectName;
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
    private final List<Long> rowIds = new ArrayList<>();
    private BigDecimal amount = BigDecimal.ZERO;
    private String productName;
    private String spec;
    private String model;
    private String businessUnit;
    private String subjectCode;
    private String subjectName;

    private Aggregate(String parentCode, String period) {
      this.parentCode = parentCode;
      this.period = period;
    }

    private void fillMeta(String productName, String spec, String model, String businessUnit) {
      if (!StringUtils.hasText(this.productName)) {
        this.productName = productName;
      }
      if (!StringUtils.hasText(this.spec)) {
        this.spec = spec;
      }
      if (!StringUtils.hasText(this.model)) {
        this.model = model;
      }
      if (!StringUtils.hasText(this.businessUnit)) {
        this.businessUnit = businessUnit;
      }
    }

    private void fillSubject(String subjectCode, String subjectName) {
      if (!StringUtils.hasText(this.subjectCode)) {
        this.subjectCode = subjectCode;
      }
      if (!StringUtils.hasText(this.subjectName)) {
        this.subjectName = subjectName;
      }
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
