package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.PlanEligibility;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.service.CmsPlanEligibilityService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CmsPlanEligibilityServiceImpl implements CmsPlanEligibilityService {
  private static final String WORKING_HOUR_KEYWORD = "工时";
  private static final String AUX_SUBJECT_KEYWORD = "辅料";

  private final CmsPlanCostRawMapper planCostRawMapper;

  public CmsPlanEligibilityServiceImpl(CmsPlanCostRawMapper planCostRawMapper) {
    this.planCostRawMapper = planCostRawMapper;
  }

  @Override
  public Map<String, PlanEligibility> checkEligibility(
      Collection<String> parentCodes, Collection<String> periods, String businessUnitType) {
    return checkEligibilityInternal(null, parentCodes, periods, businessUnitType);
  }

  @Override
  public Map<String, PlanEligibility> checkEligibility(
      Long importBatchId, Collection<String> parentCodes, Collection<String> periods) {
    if (importBatchId == null) {
      throw new IllegalArgumentException("importBatchId 不能为空");
    }
    return checkEligibilityInternal(importBatchId, parentCodes, periods, null);
  }

  private Map<String, PlanEligibility> checkEligibilityInternal(
      Long importBatchId, Collection<String> parentCodes, Collection<String> periods, String businessUnitType) {
    List<String> normalizedParentCodes = normalize(parentCodes);
    List<String> normalizedPeriods = normalize(periods);
    Map<String, PlanEligibility> result = new LinkedHashMap<>();
    for (String parentCode : normalizedParentCodes) {
      for (String period : normalizedPeriods) {
        result.put(key(parentCode, period), PlanEligibility.allowed(parentCode, period, "计划成本未找到对应未审批项，允许生效"));
      }
    }
    if (normalizedParentCodes.isEmpty() || normalizedPeriods.isEmpty()) {
      return result;
    }

    List<CmsPlanCostRaw> rows =
        planCostRawMapper.selectList(
            new QueryWrapper<CmsPlanCostRaw>()
                .eq(importBatchId != null, "import_batch_id", importBatchId)
                .in("parent_code", normalizedParentCodes)
                .in("effective_period", normalizedPeriods));
    Map<String, List<CmsPlanCostRaw>> grouped = new LinkedHashMap<>();
    for (CmsPlanCostRaw row : rows) {
      if (StringUtils.hasText(row.getParentCode()) && StringUtils.hasText(row.getEffectivePeriod())) {
        grouped.computeIfAbsent(key(row.getParentCode(), row.getEffectivePeriod()), ignored -> new ArrayList<>()).add(row);
      }
    }

    for (Map.Entry<String, List<CmsPlanCostRaw>> entry : grouped.entrySet()) {
      result.put(entry.getKey(), evaluate(entry.getValue()));
    }
    return result;
  }

  @Override
  public Map<String, PlanEligibility> checkDirectLaborEligibility(
      Long importBatchId, Collection<String> parentCodes) {
    if (importBatchId == null) {
      throw new IllegalArgumentException("importBatchId 不能为空");
    }
    List<String> normalizedParentCodes = normalize(parentCodes);
    Map<String, PlanEligibility> result = new LinkedHashMap<>();
    for (String parentCode : normalizedParentCodes) {
      result.put(parentCode, PlanEligibility.allowed(parentCode, "计划成本未找到未审批工时，允许直接人工取数"));
    }
    if (normalizedParentCodes.isEmpty()) {
      return result;
    }
    List<CmsPlanCostRaw> rows =
        planCostRawMapper.selectList(
            new QueryWrapper<CmsPlanCostRaw>()
                .eq("import_batch_id", importBatchId)
                .in("parent_code", normalizedParentCodes));
    Map<String, List<CmsPlanCostRaw>> byParent = new LinkedHashMap<>();
    for (CmsPlanCostRaw row : rows) {
      if (StringUtils.hasText(row.getParentCode())) {
        byParent.computeIfAbsent(row.getParentCode(), ignored -> new ArrayList<>()).add(row);
      }
    }
    for (String parentCode : normalizedParentCodes) {
      List<CmsPlanCostRaw> parentRows = byParent.get(parentCode);
      if (parentRows == null || parentRows.isEmpty()) {
        continue;
      }
      PlanEligibility eligibility = evaluateEarliest(parentRows);
      eligibility.setParentCode(parentCode);
      result.put(parentCode, eligibility);
    }
    return result;
  }

  private PlanEligibility evaluate(List<CmsPlanCostRaw> rows) {
    List<CmsPlanCostRaw> sortedRows = new ArrayList<>(rows);
    sortedRows.sort(rowComparator());
    CmsPlanCostRaw sourceRow = sortedRows.get(0);
    String parentCode = sourceRow.getParentCode();
    String period = sourceRow.getEffectivePeriod();
    boolean workingHourBlocked =
        sortedRows.stream().anyMatch(row -> contains(row.getUnapprovedItems(), WORKING_HOUR_KEYWORD));
    boolean auxBlocked =
        sortedRows.stream().anyMatch(row -> contains(row.getUnapprovedItems(), AUX_SUBJECT_KEYWORD));
    return PlanEligibility.fromPlanRow(
        parentCode,
        period,
        sourceRow.getId(),
        sourceRow.getEffectiveDate(),
        period,
        !workingHourBlocked,
        !auxBlocked,
        !auxBlocked,
        buildReason(workingHourBlocked, auxBlocked));
  }

  private PlanEligibility evaluateEarliest(List<CmsPlanCostRaw> rows) {
    rows.sort(rowComparator());
    String earliestPeriod = periodOrMax(rows.get(0));
    List<CmsPlanCostRaw> periodRows =
        rows.stream().filter(row -> periodOrMax(row).equals(earliestPeriod)).toList();
    return evaluate(periodRows);
  }

  private Comparator<CmsPlanCostRaw> rowComparator() {
    return Comparator.comparing(CmsPlanEligibilityServiceImpl::periodOrMax)
        .thenComparing(row -> row.getRowNo() == null ? Integer.MAX_VALUE : row.getRowNo())
        .thenComparing(row -> row.getId() == null ? Long.MAX_VALUE : row.getId());
  }

  private static String periodOrMax(CmsPlanCostRaw row) {
    if (StringUtils.hasText(row.getEffectivePeriod())) {
      return row.getEffectivePeriod();
    }
    LocalDate effectiveDate = row.getEffectiveDate();
    return effectiveDate == null ? "9999-12" : effectiveDate.toString().substring(0, 7);
  }

  private boolean contains(String unapprovedItems, String keyword) {
    return StringUtils.hasText(unapprovedItems) && unapprovedItems.contains(keyword);
  }

  private String buildReason(boolean workingHourBlocked, boolean auxBlocked) {
    if (workingHourBlocked && auxBlocked) {
      return "工时、辅料未审批";
    }
    if (workingHourBlocked) {
      return "工时未审批";
    }
    if (auxBlocked) {
      return "辅料未审批";
    }
    return "允许生效";
  }

  private String key(String parentCode, String period) {
    return parentCode + "|" + period;
  }

  private List<String> normalize(Collection<String> values) {
    List<String> normalized = new ArrayList<>();
    if (values == null) {
      return normalized;
    }
    for (String value : values) {
      if (!StringUtils.hasText(value)) {
        continue;
      }
      String trimmed = value.trim();
      if (!normalized.contains(trimmed)) {
        normalized.add(trimmed);
      }
    }
    return normalized;
  }
}
