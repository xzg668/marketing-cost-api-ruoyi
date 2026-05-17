package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.CmsCostBatchPageResponse;
import com.sanhua.marketingcost.dto.CmsCostRawPageResponse;
import com.sanhua.marketingcost.dto.CmsCostSourceEffectiveLogPageResponse;
import com.sanhua.marketingcost.dto.CmsCostSourceEffectivePageResponse;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.service.CmsCostEffectiveSourceEnsureService;
import com.sanhua.marketingcost.service.CmsCostQueryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CmsCostQueryServiceImpl implements CmsCostQueryService {
  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 200;
  private static final String AUTO_OPERATOR = "SYSTEM_AUTO";
  private static final String SALARY_DIRECT = "SALARY_DIRECT";
  private static final String SALARY_INDIRECT = "SALARY_INDIRECT";
  private static final String DIRECT_LABOR_SUBJECT_CODE = "0301";
  private static final String DIRECT_LABOR_SUBJECT_NAME = "直接人工工资";
  private static final String INDIRECT_LABOR_SUBJECT_NAME = "辅助人员工资";

  private final CmsCostImportBatchMapper batchMapper;
  private final CmsPlanCostRawMapper planCostRawMapper;
  private final CmsWorkshopLaborRawMapper workshopLaborRawMapper;
  private final CmsProductSubjectCostRawMapper productSubjectCostRawMapper;
  private final CmsSubjectSettingRawMapper subjectSettingRawMapper;
  private final CmsCostSourceEffectiveMapper effectiveMapper;
  private final CmsCostSourceEffectiveLogMapper effectiveLogMapper;
  private final CmsCostEffectiveSourceEnsureService ensureService;

  public CmsCostQueryServiceImpl(
      CmsCostImportBatchMapper batchMapper,
      CmsPlanCostRawMapper planCostRawMapper,
      CmsWorkshopLaborRawMapper workshopLaborRawMapper,
      CmsProductSubjectCostRawMapper productSubjectCostRawMapper,
      CmsSubjectSettingRawMapper subjectSettingRawMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      CmsCostSourceEffectiveLogMapper effectiveLogMapper,
      CmsCostEffectiveSourceEnsureService ensureService) {
    this.batchMapper = batchMapper;
    this.planCostRawMapper = planCostRawMapper;
    this.workshopLaborRawMapper = workshopLaborRawMapper;
    this.productSubjectCostRawMapper = productSubjectCostRawMapper;
    this.subjectSettingRawMapper = subjectSettingRawMapper;
    this.effectiveMapper = effectiveMapper;
    this.effectiveLogMapper = effectiveLogMapper;
    this.ensureService = ensureService;
  }

  @Override
  public CmsCostBatchPageResponse pageBatches(
      String batchNo, String status, int current, int size, String businessUnitType) {
    QueryWrapper<CmsCostImportBatch> query =
        new QueryWrapper<CmsCostImportBatch>()
            .like(StringUtils.hasText(batchNo), "batch_no", trim(batchNo))
            .eq(StringUtils.hasText(status), "status", trim(status))
            .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType))
            .orderByDesc("id");
    Page<CmsCostImportBatch> page = batchMapper.selectPage(new Page<>(pageNo(current), pageSize(size)), query);
    return new CmsCostBatchPageResponse(page.getTotal(), page.getRecords());
  }

  @Override
  public CmsCostRawPageResponse<CmsPlanCostRaw> pagePlanRows(
      String batchNo,
      String parentCode,
      Integer costYear,
      String period,
      int current,
      int size,
      String businessUnitType) {
    List<Long> batchIds = findBatchIds(batchNo, businessUnitType);
    if (batchNoMissing(batchNo, batchIds)) {
      return emptyRaw("PLAN");
    }
    QueryWrapper<CmsPlanCostRaw> query =
        new QueryWrapper<CmsPlanCostRaw>()
            .in(!batchIds.isEmpty(), "import_batch_id", batchIds)
            .like(StringUtils.hasText(parentCode), "parent_code", trim(parentCode))
            .likeRight(costYear != null && !StringUtils.hasText(period), "effective_period", costYear + "-")
            .eq(StringUtils.hasText(period), "effective_period", trim(period))
            .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType))
            .orderByDesc("id");
    Page<CmsPlanCostRaw> page =
        planCostRawMapper.selectPage(new Page<>(pageNo(current), pageSize(size)), query);
    return new CmsCostRawPageResponse<>("PLAN", page.getTotal(), page.getRecords());
  }

  @Override
  public CmsCostRawPageResponse<CmsWorkshopLaborRaw> pageWorkshopRows(
      String batchNo,
      String parentCode,
      Integer costYear,
      String period,
      int current,
      int size,
      String businessUnitType) {
    List<Long> batchIds = findBatchIds(batchNo, businessUnitType);
    if (batchNoMissing(batchNo, batchIds)) {
      return emptyRaw("WORKSHOP");
    }
    QueryWrapper<CmsWorkshopLaborRaw> query =
        new QueryWrapper<CmsWorkshopLaborRaw>()
            .in(!batchIds.isEmpty(), "import_batch_id", batchIds)
            .like(StringUtils.hasText(parentCode), "parent_code", trim(parentCode))
            .likeRight(costYear != null && !StringUtils.hasText(period), "period", costYear + "-")
            .eq(StringUtils.hasText(period), "period", trim(period))
            .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType))
            .orderByDesc("id");
    Page<CmsWorkshopLaborRaw> page =
        workshopLaborRawMapper.selectPage(new Page<>(pageNo(current), pageSize(size)), query);
    return new CmsCostRawPageResponse<>("WORKSHOP", page.getTotal(), page.getRecords());
  }

  @Override
  public CmsCostRawPageResponse<CmsProductSubjectCostRaw> pageSubjectRows(
      String batchNo,
      String parentCode,
      Integer costYear,
      String period,
      String subjectCode,
      String subjectName,
      int current,
      int size,
      String businessUnitType) {
    List<Long> batchIds = findBatchIds(batchNo, businessUnitType);
    if (batchNoMissing(batchNo, batchIds)) {
      return emptyRaw("SUBJECT");
    }
    QueryWrapper<CmsProductSubjectCostRaw> query =
        new QueryWrapper<CmsProductSubjectCostRaw>()
            .in(!batchIds.isEmpty(), "import_batch_id", batchIds)
            .like(StringUtils.hasText(parentCode), "parent_code", trim(parentCode))
            .likeRight(costYear != null && !StringUtils.hasText(period), "period", costYear + "-")
            .eq(StringUtils.hasText(period), "period", trim(period))
            .eq(StringUtils.hasText(subjectCode), "second_subject_code", trim(subjectCode))
            .and(
                StringUtils.hasText(subjectName),
                wrapper ->
                    wrapper
                        .like("first_subject_name", trim(subjectName))
                        .or()
                        .like("second_subject_name", trim(subjectName))
                        .or()
                        .like("third_subject_name", trim(subjectName)))
            .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType))
            .orderByDesc("id");
    Page<CmsProductSubjectCostRaw> page =
        productSubjectCostRawMapper.selectPage(new Page<>(pageNo(current), pageSize(size)), query);
    return new CmsCostRawPageResponse<>("SUBJECT", page.getTotal(), page.getRecords());
  }

  @Override
  public CmsCostRawPageResponse<CmsSubjectSettingRaw> pageSubjectSettings(
      String batchNo,
      String firstSubjectName,
      String secondSubjectCode,
      String secondSubjectName,
      int current,
      int size,
      String businessUnitType) {
    List<Long> batchIds = findBatchIds(batchNo, businessUnitType);
    if (batchNoMissing(batchNo, batchIds)) {
      return emptyRaw("SUBJECT_SETTING");
    }
    QueryWrapper<CmsSubjectSettingRaw> query =
        new QueryWrapper<CmsSubjectSettingRaw>()
            .in(!batchIds.isEmpty(), "import_batch_id", batchIds)
            .like(StringUtils.hasText(firstSubjectName), "first_subject_name", trim(firstSubjectName))
            .eq(StringUtils.hasText(secondSubjectCode), "second_subject_code", trim(secondSubjectCode))
            .like(StringUtils.hasText(secondSubjectName), "second_subject_name", trim(secondSubjectName))
            .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType))
            .orderByAsc("first_subject_code")
            .orderByAsc("second_subject_code")
            .orderByAsc("third_subject_code")
            .orderByDesc("id");
    Page<CmsSubjectSettingRaw> page =
        subjectSettingRawMapper.selectPage(new Page<>(pageNo(current), pageSize(size)), query);
    return new CmsCostRawPageResponse<>("SUBJECT_SETTING", page.getTotal(), page.getRecords());
  }

  @Override
  public CmsCostSourceEffectivePageResponse pageEffectiveSources(
      Integer costYear,
      String parentCode,
      String period,
      String sourceType,
      String subjectCode,
      int current,
      int size,
      String businessUnitType) {
    if (costYear != null) {
      ensureService.ensureDefaultSources(costYear, AUTO_OPERATOR, normalizeBusinessUnit(businessUnitType));
    }
    QueryWrapper<CmsCostSourceEffective> query =
        new QueryWrapper<CmsCostSourceEffective>()
            .eq(costYear != null, "cost_year", costYear)
            .eq(StringUtils.hasText(period), "period", trim(period))
            .eq(StringUtils.hasText(sourceType), "source_type", trim(sourceType))
            .eq(StringUtils.hasText(subjectCode), "subject_code", trim(subjectCode))
            .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType));
    applyParentCodeFilter(query, parentCode);
    query.orderByDesc("id");
    Page<CmsCostSourceEffective> page =
        effectiveMapper.selectPage(new Page<>(pageNo(current), pageSize(size)), query);
    fillSalarySubjectDisplay(page.getRecords());
    fillUnapprovedItems(page.getRecords(), businessUnitType);
    return new CmsCostSourceEffectivePageResponse(page.getTotal(), page.getRecords());
  }

  @Override
  public CmsCostSourceEffectiveLogPageResponse pageEffectiveSourceLogs(
      Integer costYear,
      String parentCode,
      String period,
      String sourceType,
      String subjectCode,
      String actionType,
      int current,
      int size,
      String businessUnitType) {
    QueryWrapper<CmsCostSourceEffectiveLog> query =
        new QueryWrapper<CmsCostSourceEffectiveLog>()
            .eq(costYear != null, "cost_year", costYear)
            .eq(StringUtils.hasText(period), "new_period", trim(period))
            .eq(StringUtils.hasText(sourceType), "source_type", trim(sourceType))
            .eq(StringUtils.hasText(subjectCode), "subject_code", trim(subjectCode))
            .eq(StringUtils.hasText(actionType), "action_type", trim(actionType))
            .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType));
    applyParentCodeFilter(query, parentCode);
    query.orderByDesc("id");
    Page<CmsCostSourceEffectiveLog> page =
        effectiveLogMapper.selectPage(new Page<>(pageNo(current), pageSize(size)), query);
    fillSalaryLogSubjectDisplay(page.getRecords());
    return new CmsCostSourceEffectiveLogPageResponse(page.getTotal(), page.getRecords());
  }

  private void fillSalarySubjectDisplay(List<CmsCostSourceEffective> rows) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    LinkedHashSet<Long> indirectSourceIds = new LinkedHashSet<>();
    for (CmsCostSourceEffective row : rows) {
      if (SALARY_DIRECT.equals(row.getSourceType())) {
        row.setSubjectCode(DIRECT_LABOR_SUBJECT_CODE);
        row.setSubjectName(DIRECT_LABOR_SUBJECT_NAME);
      } else if (SALARY_INDIRECT.equals(row.getSourceType())
          && (!StringUtils.hasText(row.getSubjectCode()) || !StringUtils.hasText(row.getSubjectName()))
          && StringUtils.hasText(row.getSourceRowIds())) {
        indirectSourceIds.addAll(parseLongIds(row.getSourceRowIds()));
      }
    }
    if (indirectSourceIds.isEmpty()) {
      return;
    }
    Map<Long, CmsProductSubjectCostRaw> rawById = new LinkedHashMap<>();
    List<CmsProductSubjectCostRaw> rawRows = productSubjectCostRawMapper.selectBatchIds(indirectSourceIds);
    if (rawRows != null) {
      for (CmsProductSubjectCostRaw rawRow : rawRows) {
        if (rawRow.getId() != null) {
          rawById.put(rawRow.getId(), rawRow);
        }
      }
    }
    for (CmsCostSourceEffective row : rows) {
      if (!SALARY_INDIRECT.equals(row.getSourceType())) {
        continue;
      }
      if (StringUtils.hasText(row.getSubjectCode()) && StringUtils.hasText(row.getSubjectName())) {
        continue;
      }
      for (Long id : parseLongIds(row.getSourceRowIds())) {
        CmsProductSubjectCostRaw rawRow = rawById.get(id);
        if (rawRow != null) {
          row.setSubjectCode(rawRow.getSecondSubjectCode());
          row.setSubjectName(rawRow.getSecondSubjectName());
          break;
        }
      }
    }
  }

  private void fillSalaryLogSubjectDisplay(List<CmsCostSourceEffectiveLog> rows) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    for (CmsCostSourceEffectiveLog row : rows) {
      if (SALARY_DIRECT.equals(row.getSourceType())) {
        row.setSubjectCode(DIRECT_LABOR_SUBJECT_CODE);
        row.setSubjectName(DIRECT_LABOR_SUBJECT_NAME);
      } else if (SALARY_INDIRECT.equals(row.getSourceType())) {
        if (!StringUtils.hasText(row.getSubjectName())) {
          row.setSubjectName(INDIRECT_LABOR_SUBJECT_NAME);
        }
      }
    }
  }

  private List<Long> parseLongIds(String sourceRowIds) {
    if (!StringUtils.hasText(sourceRowIds)) {
      return Collections.emptyList();
    }
    List<Long> ids = new ArrayList<>();
    for (String value : trim(sourceRowIds).split("[,，;；\\s]+")) {
      if (!StringUtils.hasText(value)) {
        continue;
      }
      try {
        ids.add(Long.parseLong(value.trim()));
      } catch (NumberFormatException ignored) {
        // 老数据可能不是纯数字 ID，展示字段无法回查时跳过。
      }
    }
    return ids;
  }

  private <T> void applyParentCodeFilter(QueryWrapper<T> query, String parentCode) {
    List<String> parentCodes = splitParentCodes(parentCode);
    if (parentCodes.isEmpty()) {
      return;
    }
    if (parentCodes.size() == 1) {
      query.like("parent_code", parentCodes.get(0));
      return;
    }
    query.in("parent_code", parentCodes);
  }

  private List<String> splitParentCodes(String parentCode) {
    if (!StringUtils.hasText(parentCode)) {
      return Collections.emptyList();
    }
    LinkedHashSet<String> codes = new LinkedHashSet<>();
    for (String code : trim(parentCode).split("[,，;；\\s]+")) {
      if (StringUtils.hasText(code)) {
        codes.add(code.trim());
      }
    }
    return new ArrayList<>(codes);
  }

  private void fillUnapprovedItems(List<CmsCostSourceEffective> rows, String businessUnitType) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    LinkedHashSet<String> parentCodes = new LinkedHashSet<>();
    LinkedHashSet<String> periods = new LinkedHashSet<>();
    for (CmsCostSourceEffective row : rows) {
      if (StringUtils.hasText(row.getParentCode()) && StringUtils.hasText(row.getPeriod())) {
        parentCodes.add(row.getParentCode());
        periods.add(row.getPeriod());
      }
    }
    if (parentCodes.isEmpty() || periods.isEmpty()) {
      return;
    }
    List<CmsPlanCostRaw> planRows =
        planCostRawMapper.selectList(
            new QueryWrapper<CmsPlanCostRaw>()
                .in("parent_code", parentCodes)
                .in("effective_period", periods)
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType)));
    if (planRows == null || planRows.isEmpty()) {
      return;
    }
    Map<String, LinkedHashSet<String>> unapprovedByParentPeriod = new LinkedHashMap<>();
    for (CmsPlanCostRaw planRow : planRows) {
      if (!StringUtils.hasText(planRow.getParentCode()) || !StringUtils.hasText(planRow.getEffectivePeriod())) {
        continue;
      }
      LinkedHashSet<String> items =
          unapprovedByParentPeriod.computeIfAbsent(
              key(planRow.getParentCode(), planRow.getEffectivePeriod()), ignored -> new LinkedHashSet<>());
      if (StringUtils.hasText(planRow.getUnapprovedItems())) {
        items.add(trim(planRow.getUnapprovedItems()));
      }
    }
    for (CmsCostSourceEffective row : rows) {
      LinkedHashSet<String> items = unapprovedByParentPeriod.get(key(row.getParentCode(), row.getPeriod()));
      if (items != null && !items.isEmpty()) {
        row.setUnapprovedItems(String.join(";", items));
      }
    }
  }

  private String key(String parentCode, String period) {
    return trim(parentCode) + "|" + trim(period);
  }

  private List<Long> findBatchIds(String batchNo, String businessUnitType) {
    if (!StringUtils.hasText(batchNo)) {
      return Collections.emptyList();
    }
    List<CmsCostImportBatch> batches =
        batchMapper.selectList(
            new QueryWrapper<CmsCostImportBatch>()
                .like("batch_no", trim(batchNo))
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType))
                .orderByDesc("id"));
    return batches.stream().map(CmsCostImportBatch::getId).toList();
  }

  private boolean batchNoMissing(String batchNo, List<Long> batchIds) {
    return StringUtils.hasText(batchNo) && (batchIds == null || batchIds.isEmpty());
  }

  private <T> CmsCostRawPageResponse<T> emptyRaw(String rawType) {
    return new CmsCostRawPageResponse<>(rawType, 0, Collections.emptyList());
  }

  private int pageNo(int current) {
    return current <= 0 ? DEFAULT_PAGE : current;
  }

  private int pageSize(int size) {
    if (size <= 0) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  private String trim(String value) {
    return value == null ? null : value.trim();
  }

  private String normalizeBusinessUnit(String businessUnitType) {
    return businessUnitType == null ? "" : businessUnitType;
  }
}
