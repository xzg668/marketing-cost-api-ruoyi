package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.CmsSalaryCostDeriveResponse;
import com.sanhua.marketingcost.dto.PlanEligibility;
import com.sanhua.marketingcost.entity.CmsCostDeriveLog;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.entity.SalaryCost;
import com.sanhua.marketingcost.mapper.CmsCostDeriveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.mapper.SalaryCostMapper;
import com.sanhua.marketingcost.service.CmsPlanEligibilityService;
import com.sanhua.marketingcost.service.CmsSalaryCostDeriveService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CmsSalaryCostDeriveServiceImpl implements CmsSalaryCostDeriveService {
  private static final String SOURCE_CMS = "CMS";
  private static final String FIRST_SUBJECT_SALARY = "工资";
  private static final String INDIRECT_LABOR_SUBJECT_NAME = "辅助人员工资";
  private static final BigDecimal CENT_TO_YUAN = new BigDecimal("100");

  private final CmsCostImportBatchMapper batchMapper;
  private final CmsWorkshopLaborRawMapper workshopLaborRawMapper;
  private final CmsProductSubjectCostRawMapper productSubjectCostRawMapper;
  private final SalaryCostMapper salaryCostMapper;
  private final CmsCostDeriveLogMapper deriveLogMapper;
  private final CmsPlanEligibilityService planEligibilityService;

  public CmsSalaryCostDeriveServiceImpl(
      CmsCostImportBatchMapper batchMapper,
      CmsWorkshopLaborRawMapper workshopLaborRawMapper,
      CmsProductSubjectCostRawMapper productSubjectCostRawMapper,
      SalaryCostMapper salaryCostMapper,
      CmsCostDeriveLogMapper deriveLogMapper,
      CmsPlanEligibilityService planEligibilityService) {
    this.batchMapper = batchMapper;
    this.workshopLaborRawMapper = workshopLaborRawMapper;
    this.productSubjectCostRawMapper = productSubjectCostRawMapper;
    this.salaryCostMapper = salaryCostMapper;
    this.deriveLogMapper = deriveLogMapper;
    this.planEligibilityService = planEligibilityService;
  }

  @Override
  @Transactional
  public CmsSalaryCostDeriveResponse deriveSalaryCosts(Long importBatchId) {
    if (importBatchId == null) {
      throw new IllegalArgumentException("importBatchId 不能为空");
    }
    CmsCostImportBatch batch = batchMapper.selectById(importBatchId);
    if (batch == null) {
      throw new IllegalArgumentException("CMS 导入批次不存在: " + importBatchId);
    }
    String businessUnitType = batch.getBusinessUnitType();
    Map<String, SalaryAggregate> directAggregates =
        aggregateDirect(importBatchId, businessUnitType);
    Map<String, SalaryAggregate> indirectAggregates =
        aggregateIndirect(importBatchId, businessUnitType);

    Set<String> parentCodes = new LinkedHashSet<>();
    parentCodes.addAll(directAggregates.keySet());
    parentCodes.addAll(indirectAggregates.keySet());
    Map<String, PlanEligibility> eligibilityMap =
        planEligibilityService.checkDirectLaborEligibility(importBatchId, parentCodes);

    CmsSalaryCostDeriveResponse response = new CmsSalaryCostDeriveResponse();
    response.setImportBatchId(importBatchId);
    for (String parentCode : parentCodes) {
      SalaryAggregate direct = directAggregates.get(parentCode);
      SalaryAggregate indirect = indirectAggregates.get(parentCode);
      PlanEligibility eligibility = eligibilityMap.get(parentCode);
      boolean directAllowed = eligibility == null || eligibility.isDirectLaborAllowed();
      boolean indirectAllowed = eligibility == null || eligibility.isIndirectLaborAllowed();
      if (!directAllowed && direct != null) {
        response.setSalaryBlockedCount(response.getSalaryBlockedCount() + 1);
        insertLog(
            importBatchId,
            "SALARY_DIRECT",
            parentCode,
            direct.period,
            "BLOCKED",
            eligibility.getReason(),
            direct.amount,
            null,
            null,
            businessUnitType);
      }
      if (!indirectAllowed && indirect != null) {
        response.setSalaryBlockedCount(response.getSalaryBlockedCount() + 1);
        insertLog(
            importBatchId,
            "SALARY_INDIRECT",
            parentCode,
            indirect.period,
            "BLOCKED",
            eligibility.getReason(),
            indirect.amount,
            null,
            null,
            businessUnitType);
      }

      SalaryAggregate merged =
          mergeAggregates(parentCode, directAllowed ? direct : null, indirectAllowed ? indirect : null);
      if (merged == null) {
        continue;
      }
      SalaryCost existing = findExisting(parentCode, businessUnitType);
      if (existing != null) {
        response.setSalarySkipCount(response.getSalarySkipCount() + 1);
        insertLog(
            importBatchId,
            "SALARY",
            parentCode,
            merged.period,
            "SKIPPED",
            "CMS工资记录已存在，首月锁定不覆盖",
            merged.amount,
            "lp_salary_cost",
            existing.getId(),
            businessUnitType);
        continue;
      }
      SalaryCost salary =
          toSalaryCost(importBatchId, businessUnitType, merged, directAllowed ? direct : null, indirectAllowed ? indirect : null);
      salaryCostMapper.insert(salary);
      response.setSalaryInsertCount(response.getSalaryInsertCount() + 1);
      insertLog(
          importBatchId,
          "SALARY",
          parentCode,
          merged.period,
          "INSERTED",
          buildInsertedMessage(directAllowed ? direct : null, indirectAllowed ? indirect : null),
          merged.amount,
          "lp_salary_cost",
          salary.getId(),
          businessUnitType);
    }

    batch.setSalaryInsertCount(response.getSalaryInsertCount());
    batch.setSalarySkipCount(response.getSalarySkipCount());
    batch.setSalaryBlockedCount(response.getSalaryBlockedCount());
    batch.setErrorCount(response.getErrorCount());
    batch.setErrorMessage(response.getErrorMessage());
    batchMapper.updateById(batch);
    return response;
  }

  private Map<String, SalaryAggregate> aggregateDirect(Long importBatchId, String businessUnitType) {
    List<CmsWorkshopLaborRaw> rows =
        workshopLaborRawMapper.selectList(
            new QueryWrapper<CmsWorkshopLaborRaw>()
                .eq("import_batch_id", importBatchId)
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType));
    Map<String, List<CmsWorkshopLaborRaw>> grouped = new LinkedHashMap<>();
    for (CmsWorkshopLaborRaw row : rows) {
      if (StringUtils.hasText(row.getParentCode()) && StringUtils.hasText(row.getPeriod())) {
        grouped.computeIfAbsent(row.getParentCode(), ignored -> new ArrayList<>()).add(row);
      }
    }
    Map<String, SalaryAggregate> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<CmsWorkshopLaborRaw>> entry : grouped.entrySet()) {
      List<CmsWorkshopLaborRaw> parentRows = entry.getValue();
      String earliestPeriod = earliestPeriod(parentRows.stream().map(CmsWorkshopLaborRaw::getPeriod).toList());
      SalaryAggregate aggregate = new SalaryAggregate(entry.getKey(), earliestPeriod);
      parentRows.stream()
          .filter(row -> earliestPeriod.equals(row.getPeriod()))
          .sorted(Comparator.comparing(row -> row.getRowNo() == null ? Integer.MAX_VALUE : row.getRowNo()))
          .forEach(row -> {
            aggregate.amount = aggregate.amount.add(directYuan(row));
            aggregate.fillMeta(row.getParentName(), row.getParentSpec(), row.getParentType(), row.getFirstUnitName());
          });
      result.put(entry.getKey(), aggregate);
    }
    return result;
  }

  private Map<String, SalaryAggregate> aggregateIndirect(Long importBatchId, String businessUnitType) {
    List<CmsProductSubjectCostRaw> rows =
        productSubjectCostRawMapper.selectList(
            new QueryWrapper<CmsProductSubjectCostRaw>()
                .eq("import_batch_id", importBatchId)
                .eq("first_subject_name", FIRST_SUBJECT_SALARY)
                .eq("second_subject_name", INDIRECT_LABOR_SUBJECT_NAME)
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType));
    Map<String, List<CmsProductSubjectCostRaw>> grouped = new LinkedHashMap<>();
    for (CmsProductSubjectCostRaw row : rows) {
      if (StringUtils.hasText(row.getParentCode())
          && StringUtils.hasText(row.getPeriod())
          && FIRST_SUBJECT_SALARY.equals(row.getFirstSubjectName())
          && INDIRECT_LABOR_SUBJECT_NAME.equals(row.getSecondSubjectName())) {
        grouped.computeIfAbsent(row.getParentCode(), ignored -> new ArrayList<>()).add(row);
      }
    }
    Map<String, SalaryAggregate> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<CmsProductSubjectCostRaw>> entry : grouped.entrySet()) {
      List<CmsProductSubjectCostRaw> parentRows = entry.getValue();
      String earliestPeriod = earliestPeriod(parentRows.stream().map(CmsProductSubjectCostRaw::getPeriod).toList());
      SalaryAggregate aggregate = new SalaryAggregate(entry.getKey(), earliestPeriod);
      parentRows.stream()
          .filter(row -> earliestPeriod.equals(row.getPeriod()))
          .sorted(Comparator.comparing(row -> row.getRowNo() == null ? Integer.MAX_VALUE : row.getRowNo()))
          .forEach(row -> {
            aggregate.amount = aggregate.amount.add(toYuan(row.getMaterialPrice()));
            aggregate.fillMeta(row.getParentName(), row.getParentSpec(), row.getParentType(), row.getFirstUnitName());
          });
      result.put(entry.getKey(), aggregate);
    }
    return result;
  }

  private SalaryAggregate mergeAggregates(
      String parentCode, SalaryAggregate direct, SalaryAggregate indirect) {
    if (direct == null && indirect == null) {
      return null;
    }
    SalaryAggregate merged =
        direct != null
            ? new SalaryAggregate(parentCode, direct.period)
            : new SalaryAggregate(parentCode, indirect.period);
    if (direct != null && indirect != null && indirect.period.compareTo(merged.period) < 0) {
      merged.period = indirect.period;
    }
    if (direct != null) {
      merged.amount = merged.amount.add(direct.amount);
      merged.fillMeta(direct.productName, direct.spec, direct.model, direct.businessUnit);
    }
    if (indirect != null) {
      merged.amount = merged.amount.add(indirect.amount);
      merged.fillMeta(indirect.productName, indirect.spec, indirect.model, indirect.businessUnit);
    }
    return merged;
  }

  private SalaryCost toSalaryCost(
      Long importBatchId,
      String businessUnitType,
      SalaryAggregate merged,
      SalaryAggregate direct,
      SalaryAggregate indirect) {
    SalaryCost salary = new SalaryCost();
    salary.setMaterialCode(merged.parentCode);
    salary.setProductName(merged.productName);
    salary.setSpec(merged.spec);
    salary.setModel(merged.model);
    salary.setDirectLaborCost(direct == null ? BigDecimal.ZERO.setScale(6) : direct.amount.setScale(6, RoundingMode.HALF_UP));
    salary.setIndirectLaborCost(indirect == null ? BigDecimal.ZERO.setScale(6) : indirect.amount.setScale(6, RoundingMode.HALF_UP));
    salary.setSource(SOURCE_CMS);
    salary.setBusinessUnit(merged.businessUnit);
    salary.setPeriod(merged.period);
    salary.setSourceImportBatchId(importBatchId);
    salary.setLockStatus("LOCKED");
    salary.setLockReason("CMS首次期间锁定");
    salary.setBusinessUnitType(businessUnitType);
    return salary;
  }

  private SalaryCost findExisting(String parentCode, String businessUnitType) {
    QueryWrapper<SalaryCost> query =
        new QueryWrapper<SalaryCost>()
            .eq("material_code", parentCode)
            .eq("source", SOURCE_CMS)
            .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType)
            .last("LIMIT 1");
    if (!StringUtils.hasText(businessUnitType)) {
      query.isNull("business_unit_type");
    }
    return salaryCostMapper.selectOne(query);
  }

  private void insertLog(
      Long importBatchId,
      String deriveType,
      String parentCode,
      String period,
      String status,
      String message,
      BigDecimal amount,
      String targetTable,
      Long targetId,
      String businessUnitType) {
    CmsCostDeriveLog log = new CmsCostDeriveLog();
    log.setImportBatchId(importBatchId);
    log.setDeriveType(deriveType);
    log.setParentCode(parentCode);
    log.setPeriod(period);
    log.setStatus(status);
    log.setMessage(message);
    log.setAmount(amount == null ? null : amount.setScale(6, RoundingMode.HALF_UP));
    log.setTargetTable(targetTable);
    log.setTargetId(targetId);
    log.setBusinessUnitType(businessUnitType);
    deriveLogMapper.insert(log);
  }

  private BigDecimal directYuan(CmsWorkshopLaborRaw row) {
    if (row.getWorkingCostYuan() != null) {
      return row.getWorkingCostYuan();
    }
    return toYuan(row.getWorkingCostCent());
  }

  private BigDecimal toYuan(BigDecimal cent) {
    if (cent == null) {
      return BigDecimal.ZERO;
    }
    return cent.divide(CENT_TO_YUAN, 6, RoundingMode.HALF_UP);
  }

  private String earliestPeriod(List<String> periods) {
    return periods.stream().filter(StringUtils::hasText).min(String::compareTo).orElse(null);
  }

  private String buildInsertedMessage(SalaryAggregate direct, SalaryAggregate indirect) {
    if (direct == null) {
      return "CMS工资新增锁定；直接人工缺失或被阻断，仅写入辅助人工";
    }
    if (indirect == null) {
      return "CMS工资新增锁定；辅助人工缺失，仅写入直接人工";
    }
    return "CMS工资新增锁定";
  }

  private static final class SalaryAggregate {
    private final String parentCode;
    private String period;
    private BigDecimal amount = BigDecimal.ZERO;
    private String productName;
    private String spec;
    private String model;
    private String businessUnit;

    private SalaryAggregate(String parentCode, String period) {
      this.parentCode = parentCode;
      this.period = period;
    }

    private void fillMeta(String productName, String spec, String model, String businessUnit) {
      if (!StringUtils.hasText(this.productName) && StringUtils.hasText(productName)) {
        this.productName = productName;
      }
      if (!StringUtils.hasText(this.spec) && StringUtils.hasText(spec)) {
        this.spec = spec;
      }
      if (!StringUtils.hasText(this.model) && StringUtils.hasText(model)) {
        this.model = model;
      }
      if (!StringUtils.hasText(this.businessUnit) && StringUtils.hasText(businessUnit)) {
        this.businessUnit = businessUnit;
      }
    }
  }
}
