package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.DepartmentFundRateImportRequest;
import com.sanhua.marketingcost.dto.DepartmentFundRateImportResponse;
import com.sanhua.marketingcost.dto.DepartmentFundRateRequest;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.DepartmentFundRateService;
import java.math.BigDecimal;
import java.time.Year;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DepartmentFundRateServiceImpl implements DepartmentFundRateService {
  private static final String DEFAULT_BUSINESS_UNIT_TYPE = "COMMERCIAL";
  private static final String SOURCE_MANUAL = "MANUAL";
  private static final String SOURCE_EXCEL_IMPORT = "EXCEL_IMPORT";
  private static final BigDecimal ZERO = BigDecimal.ZERO;

  private final DepartmentFundRateMapper departmentFundRateMapper;

  public DepartmentFundRateServiceImpl(DepartmentFundRateMapper departmentFundRateMapper) {
    this.departmentFundRateMapper = departmentFundRateMapper;
  }

  @Override
  @Cacheable(
      value = "departmentFundRates",
      key =
          "'list:' + (#businessUnit ?: 'ALL') + ':' + (#rateYear ?: 'ALL') + ':' + (#expenseSubject ?: 'ALL')")
  public List<DepartmentFundRate> list(String businessUnit, Integer rateYear, String expenseSubject) {
    var query = Wrappers.lambdaQuery(DepartmentFundRate.class);
    if (StringUtils.hasText(businessUnit)) {
      String value = businessUnit.trim();
      query.and(
          q -> q.like(DepartmentFundRate::getBusinessDivision, value)
              .or()
              .like(DepartmentFundRate::getBusinessUnit, value));
    }
    if (rateYear != null) {
      query.eq(DepartmentFundRate::getRateYear, rateYear);
    }
    if (StringUtils.hasText(expenseSubject)) {
      query.like(DepartmentFundRate::getExpenseSubject, expenseSubject.trim());
    }
    query.orderByDesc(DepartmentFundRate::getRateYear)
        .orderByAsc(DepartmentFundRate::getBusinessDivision)
        .orderByAsc(DepartmentFundRate::getExpenseSubject)
        .orderByDesc(DepartmentFundRate::getId);
    return departmentFundRateMapper.selectList(query);
  }

  @Override
  @CacheEvict(value = "departmentFundRates", allEntries = true)
  public DepartmentFundRate create(DepartmentFundRateRequest request) {
    if (request == null) {
      return null;
    }
    DepartmentFundRate entity = new DepartmentFundRate();
    merge(entity, request);
    fillDefaults(entity, null, SOURCE_MANUAL);
    if (!hasRequired(entity)) {
      return null;
    }
    DepartmentFundRate existing = findExisting(entity);
    if (existing == null) {
      departmentFundRateMapper.insert(entity);
      return entity;
    }
    merge(existing, entity);
    departmentFundRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "departmentFundRates", allEntries = true)
  public DepartmentFundRate update(Long id, DepartmentFundRateRequest request) {
    if (id == null) {
      return null;
    }
    DepartmentFundRate existing = departmentFundRateMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing, null, SOURCE_MANUAL);
    if (!hasRequired(existing)) {
      return null;
    }
    departmentFundRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "departmentFundRates", allEntries = true)
  public boolean delete(Long id) {
    return id != null && departmentFundRateMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "departmentFundRates", allEntries = true)
  public DepartmentFundRateImportResponse importItems(DepartmentFundRateImportRequest request) {
    DepartmentFundRateImportResponse response = new DepartmentFundRateImportResponse();
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return response;
    }
    int index = 1;
    for (var row : request.getRows()) {
      int rowNo = row != null && row.getRowNo() != null ? row.getRowNo() : index;
      index++;
      if (row == null || isBlankRow(row)) {
        response.incrementSkipped();
        continue;
      }
      DepartmentFundRate entity = new DepartmentFundRate();
      fillFromRow(entity, row, request);
      fillDefaults(entity, request, SOURCE_EXCEL_IMPORT);
      String validationError = validate(entity, rowNo);
      if (validationError != null) {
        response.addError(validationError);
        response.incrementSkipped();
        continue;
      }
      DepartmentFundRate existing = findExisting(entity);
      if (existing == null) {
        departmentFundRateMapper.insert(entity);
        response.incrementInserted();
        response.addRecord(entity);
      } else {
        merge(existing, entity);
        departmentFundRateMapper.updateById(existing);
        response.incrementUpdated();
        response.addRecord(existing);
      }
    }
    return response;
  }

  private void fillFromRow(
      DepartmentFundRate entity,
      DepartmentFundRateImportRequest.DepartmentFundRateRow row,
      DepartmentFundRateImportRequest request) {
    entity.setBusinessUnit(firstText(row.getBusinessUnit(), row.getBusinessDivision()));
    entity.setBusinessDivision(firstText(row.getBusinessDivision(), row.getBusinessUnit()));
    entity.setExpenseSubject(row.getExpenseSubject());
    entity.setBudgetAmount(row.getBudgetAmount());
    entity.setTotalWorkMinutes(row.getTotalWorkMinutes());
    entity.setPlanRate(row.getPlanRate());
    entity.setUpliftRatio(row.getUpliftRatio());
    entity.setQuoteRatio(row.getQuoteRatio());
    entity.setManhourRate(row.getManhourRate());
    entity.setRateYear(row.getRateYear() != null ? row.getRateYear() : request.getRateYear());
    entity.setRemark(row.getRemark());
    entity.setBusinessUnitType(resolveBusinessUnitType(request.getBusinessUnitType()));
    entity.setSourceBatchNo(request.getSourceBatchNo());
    entity.setOverhaulRate(row.getOverhaulRate());
    entity.setToolingRepairRate(row.getToolingRepairRate());
    entity.setWaterPowerRate(row.getWaterPowerRate());
    entity.setOtherRate(row.getOtherRate());
    entity.setUpliftRate(row.getUpliftRate());
  }

  private void merge(DepartmentFundRate entity, DepartmentFundRateRequest request) {
    if (request == null) {
      return;
    }
    if (request.getBusinessUnit() != null) {
      entity.setBusinessUnit(request.getBusinessUnit());
    }
    if (request.getBusinessDivision() != null) {
      entity.setBusinessDivision(request.getBusinessDivision());
    }
    if (request.getExpenseSubject() != null) {
      entity.setExpenseSubject(request.getExpenseSubject());
    }
    if (request.getBudgetAmount() != null) {
      entity.setBudgetAmount(request.getBudgetAmount());
    }
    if (request.getTotalWorkMinutes() != null) {
      entity.setTotalWorkMinutes(request.getTotalWorkMinutes());
    }
    if (request.getPlanRate() != null) {
      entity.setPlanRate(request.getPlanRate());
    }
    if (request.getUpliftRatio() != null) {
      entity.setUpliftRatio(request.getUpliftRatio());
    }
    if (request.getQuoteRatio() != null) {
      entity.setQuoteRatio(request.getQuoteRatio());
    }
    if (request.getRateYear() != null) {
      entity.setRateYear(request.getRateYear());
    }
    if (request.getBusinessUnitType() != null) {
      entity.setBusinessUnitType(request.getBusinessUnitType());
    }
    if (request.getRemark() != null) {
      entity.setRemark(request.getRemark());
    }
    if (request.getOverhaulRate() != null) {
      entity.setOverhaulRate(request.getOverhaulRate());
    }
    if (request.getToolingRepairRate() != null) {
      entity.setToolingRepairRate(request.getToolingRepairRate());
    }
    if (request.getWaterPowerRate() != null) {
      entity.setWaterPowerRate(request.getWaterPowerRate());
    }
    if (request.getOtherRate() != null) {
      entity.setOtherRate(request.getOtherRate());
    }
    if (request.getUpliftRate() != null) {
      entity.setUpliftRate(request.getUpliftRate());
    }
    if (request.getManhourRate() != null) {
      entity.setManhourRate(request.getManhourRate());
    }
  }

  private void merge(DepartmentFundRate target, DepartmentFundRate source) {
    target.setBusinessUnit(source.getBusinessUnit());
    target.setBusinessDivision(source.getBusinessDivision());
    target.setExpenseSubject(source.getExpenseSubject());
    target.setBudgetAmount(source.getBudgetAmount());
    target.setTotalWorkMinutes(source.getTotalWorkMinutes());
    target.setPlanRate(source.getPlanRate());
    target.setUpliftRatio(source.getUpliftRatio());
    target.setQuoteRatio(source.getQuoteRatio());
    target.setRateYear(source.getRateYear());
    target.setBusinessUnitType(source.getBusinessUnitType());
    target.setRemark(source.getRemark());
    target.setSourceType(source.getSourceType());
    target.setSourceBatchNo(source.getSourceBatchNo());
    target.setOverhaulRate(source.getOverhaulRate());
    target.setToolingRepairRate(source.getToolingRepairRate());
    target.setWaterPowerRate(source.getWaterPowerRate());
    target.setOtherRate(source.getOtherRate());
    target.setUpliftRate(source.getUpliftRate());
    target.setManhourRate(source.getManhourRate());
  }

  private void fillDefaults(
      DepartmentFundRate entity, DepartmentFundRateImportRequest request, String sourceType) {
    entity.setBusinessDivision(trimToNull(firstText(entity.getBusinessDivision(), entity.getBusinessUnit())));
    entity.setBusinessUnit(legacyRequiredText(firstText(entity.getBusinessUnit(), entity.getBusinessDivision())));
    entity.setExpenseSubject(trimToNull(entity.getExpenseSubject()));
    entity.setBusinessUnitType(resolveBusinessUnitType(entity.getBusinessUnitType()));
    entity.setRemark(trimToNull(entity.getRemark()));
    entity.setSourceType(sourceType);
    entity.setSourceBatchNo(trimToNull(entity.getSourceBatchNo()));
    if (entity.getRateYear() == null && request != null) {
      entity.setRateYear(request.getRateYear());
    }
    if (entity.getRateYear() == null) {
      entity.setRateYear(Year.now().getValue());
    }
    entity.setOverhaulRate(defaultDecimal(entity.getOverhaulRate()));
    entity.setToolingRepairRate(defaultDecimal(entity.getToolingRepairRate()));
    entity.setWaterPowerRate(defaultDecimal(entity.getWaterPowerRate()));
    entity.setOtherRate(defaultDecimal(entity.getOtherRate()));
    entity.setUpliftRate(defaultDecimal(firstDecimal(entity.getUpliftRate(), entity.getUpliftRatio())));
    entity.setManhourRate(defaultDecimal(entity.getManhourRate()));
    syncLegacySubjectRate(entity);
  }

  private void syncLegacySubjectRate(DepartmentFundRate entity) {
    if (entity.getQuoteRatio() == null || !StringUtils.hasText(entity.getExpenseSubject())) {
      return;
    }
    String subject = entity.getExpenseSubject();
    if (subject.contains("大修")) {
      entity.setOverhaulRate(entity.getQuoteRatio());
    } else if (subject.contains("工装")) {
      entity.setToolingRepairRate(entity.getQuoteRatio());
    } else if (subject.contains("水电")) {
      entity.setWaterPowerRate(entity.getQuoteRatio());
    } else {
      entity.setOtherRate(entity.getQuoteRatio());
    }
  }

  private String validate(DepartmentFundRate entity, int rowNo) {
    if (entity.getRateYear() == null) {
      return "Excel第" + rowNo + "行缺年度";
    }
    if (!StringUtils.hasText(entity.getBusinessDivision())) {
      return "Excel第" + rowNo + "行缺事业部";
    }
    if (!StringUtils.hasText(entity.getExpenseSubject())) {
      return "Excel第" + rowNo + "行缺费用科目";
    }
    if (entity.getQuoteRatio() == null) {
      return "Excel第" + rowNo + "行缺报价比例（元/分钟）";
    }
    return null;
  }

  private boolean hasRequired(DepartmentFundRate entity) {
    return entity.getRateYear() != null
        && StringUtils.hasText(entity.getBusinessDivision())
        && StringUtils.hasText(entity.getExpenseSubject())
        && entity.getQuoteRatio() != null;
  }

  private DepartmentFundRate findExisting(DepartmentFundRate entity) {
    var query =
        Wrappers.lambdaQuery(DepartmentFundRate.class)
            .eq(DepartmentFundRate::getRateYear, entity.getRateYear())
            .eq(DepartmentFundRate::getBusinessDivision, entity.getBusinessDivision())
            .eq(DepartmentFundRate::getExpenseSubject, entity.getExpenseSubject());
    if (StringUtils.hasText(entity.getBusinessUnitType())) {
      query.eq(DepartmentFundRate::getBusinessUnitType, entity.getBusinessUnitType());
    } else {
      query.isNull(DepartmentFundRate::getBusinessUnitType);
    }
    return departmentFundRateMapper.selectOne(query.last("LIMIT 1"));
  }

  private boolean isBlankRow(DepartmentFundRateImportRequest.DepartmentFundRateRow row) {
    return !StringUtils.hasText(row.getBusinessDivision())
        && !StringUtils.hasText(row.getBusinessUnit())
        && !StringUtils.hasText(row.getExpenseSubject())
        && row.getBudgetAmount() == null
        && row.getTotalWorkMinutes() == null
        && row.getPlanRate() == null
        && row.getUpliftRatio() == null
        && row.getQuoteRatio() == null
        && row.getManhourRate() == null
        && !StringUtils.hasText(row.getRemark());
  }

  private String resolveBusinessUnitType(String requestValue) {
    String value = firstText(requestValue, BusinessUnitContext.getCurrentBusinessUnitType());
    return StringUtils.hasText(value) ? value : DEFAULT_BUSINESS_UNIT_TYPE;
  }

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    if (StringUtils.hasText(second)) {
      return second.trim();
    }
    return null;
  }

  private BigDecimal firstDecimal(BigDecimal first, BigDecimal second) {
    return first != null ? first : second;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String legacyRequiredText(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? "" : trimmed;
  }

  private BigDecimal defaultDecimal(BigDecimal value) {
    return value == null ? ZERO : value;
  }
}
