package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ThreeExpenseRateImportRequest;
import com.sanhua.marketingcost.dto.ThreeExpenseRateImportResponse;
import com.sanhua.marketingcost.dto.ThreeExpenseRateRequest;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import com.sanhua.marketingcost.service.ThreeExpenseRateService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThreeExpenseRateServiceImpl implements ThreeExpenseRateService {
  private final ThreeExpenseRateMapper threeExpenseRateMapper;

  public ThreeExpenseRateServiceImpl(ThreeExpenseRateMapper threeExpenseRateMapper) {
    this.threeExpenseRateMapper = threeExpenseRateMapper;
  }

  @Override
  @Cacheable(
      value = "threeExpenseRates",
      key = "{#department,#periodYear,#productCategory,#productLine,#applicantDepartment,#applicantOffice}")
  public List<ThreeExpenseRate> list(
      String department,
      Integer periodYear,
      String productCategory,
      String productLine,
      String applicantDepartment,
      String applicantOffice) {
    var query = Wrappers.lambdaQuery(ThreeExpenseRate.class);
    if (StringUtils.hasText(department)) {
      query.like(ThreeExpenseRate::getDepartment, department.trim());
    }
    if (periodYear != null) {
      query.eq(ThreeExpenseRate::getPeriodYear, periodYear);
    }
    if (StringUtils.hasText(productCategory)) {
      query.eq(ThreeExpenseRate::getProductCategory, productCategory.trim());
    }
    if (StringUtils.hasText(productLine)) {
      query.eq(ThreeExpenseRate::getProductLine, productLine.trim());
    }
    if (StringUtils.hasText(applicantDepartment)) {
      query.like(ThreeExpenseRate::getApplicantDepartment, applicantDepartment.trim());
    }
    if (StringUtils.hasText(applicantOffice)) {
      query.like(ThreeExpenseRate::getApplicantOffice, applicantOffice.trim());
    }
    query
        .orderByDesc(ThreeExpenseRate::getPeriodYear)
        .orderByDesc(ThreeExpenseRate::getPeriod)
        .orderByDesc(ThreeExpenseRate::getId);
    return threeExpenseRateMapper.selectList(query);
  }

  @Override
  @CacheEvict(value = "threeExpenseRates", allEntries = true)
  public ThreeExpenseRate create(ThreeExpenseRateRequest request) {
    if (request == null) {
      return null;
    }
    ThreeExpenseRate entity = new ThreeExpenseRate();
    merge(entity, request);
    fillDefaults(entity);
    if (!hasRequired(entity)) {
      return null;
    }
    threeExpenseRateMapper.insert(entity);
    return entity;
  }

  @Override
  @CacheEvict(value = "threeExpenseRates", allEntries = true)
  public ThreeExpenseRate update(Long id, ThreeExpenseRateRequest request) {
    if (id == null) {
      return null;
    }
    ThreeExpenseRate existing = threeExpenseRateMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    threeExpenseRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "threeExpenseRates", allEntries = true)
  public boolean delete(Long id) {
    return id != null && threeExpenseRateMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "threeExpenseRates", allEntries = true)
  public ThreeExpenseRateImportResponse importItems(ThreeExpenseRateImportRequest request) {
    ThreeExpenseRateImportResponse response = new ThreeExpenseRateImportResponse();
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return response;
    }
    response.setTotalCount(request.getRows().size());
    Map<String, ThreeExpenseRate> pending = new LinkedHashMap<>();
    int rowNumber = 1;
    for (var row : request.getRows()) {
      if (row == null) {
        response.addError("第 " + rowNumber + " 行为空");
        rowNumber++;
        continue;
      }
      ThreeExpenseRate entity = new ThreeExpenseRate();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequired(entity)) {
        response.addError("第 " + rowNumber + " 行缺少必填字段或费率");
        rowNumber++;
        continue;
      }
      String importKey = importKey(entity);
      if (pending.containsKey(importKey)) {
        response.setDuplicateOverrideCount(response.getDuplicateOverrideCount() + 1);
        response.addMessage("第 " + rowNumber + " 行与本批前序数据重复，已以后出现的数据为准");
      }
      pending.put(importKey, entity);
      rowNumber++;
    }
    if (!response.getErrors().isEmpty()) {
      return response;
    }

    List<ThreeExpenseRate> imported = new ArrayList<>();
    for (ThreeExpenseRate entity : pending.values()) {
      ThreeExpenseRate existing = findExisting(entity);
      if (existing == null) {
        threeExpenseRateMapper.insert(entity);
        response.setInsertedCount(response.getInsertedCount() + 1);
        imported.add(entity);
      } else {
        mergeImportValues(existing, entity);
        threeExpenseRateMapper.updateById(existing);
        response.setUpdatedCount(response.getUpdatedCount() + 1);
        imported.add(existing);
      }
    }
    response.setRows(imported);
    return response;
  }

  private void fillFromRow(ThreeExpenseRate entity, ThreeExpenseRateImportRequest.ThreeExpenseRateRow row) {
    entity.setCompany(row.getCompany());
    entity.setBusinessUnit(row.getBusinessUnit());
    entity.setDepartment(row.getDepartment());
    entity.setManagementExpenseRate(row.getManagementExpenseRate());
    entity.setFinanceExpenseRate(row.getFinanceExpenseRate());
    entity.setSalesExpenseRate(row.getSalesExpenseRate());
    entity.setThreeExpenseRate2025(row.getThreeExpenseRate2025());
    entity.setThreeExpenseRate2026(row.getThreeExpenseRate2026());
    entity.setOverseasSales(row.getOverseasSales());
    entity.setPeriod(row.getPeriod());
    entity.setPeriodMonth(row.getPeriodMonth());
    entity.setPeriodYear(row.getPeriodYear());
    entity.setStandardCompany(row.getStandardCompany());
    entity.setProductionDivision(row.getProductionDivision());
    entity.setApplicantDepartment(row.getApplicantDepartment());
    entity.setApplicantOffice(row.getApplicantOffice());
    entity.setProductCategory(row.getProductCategory());
    entity.setProductLine(row.getProductLine());
    entity.setThreeExpenseTotalRate(row.getThreeExpenseTotalRate());
    entity.setOemExpenseRate(row.getOemExpenseRate());
    entity.setSourceType(row.getSourceType());
    entity.setImportBatchNo(row.getImportBatchNo());
    entity.setBusinessUnitType(row.getBusinessUnitType());
  }

  private void merge(ThreeExpenseRate entity, ThreeExpenseRateRequest request) {
    if (request == null) {
      return;
    }
    if (request.getCompany() != null) {
      entity.setCompany(request.getCompany());
    }
    if (request.getBusinessUnit() != null) {
      entity.setBusinessUnit(request.getBusinessUnit());
    }
    if (request.getDepartment() != null) {
      entity.setDepartment(request.getDepartment());
    }
    if (request.getManagementExpenseRate() != null) {
      entity.setManagementExpenseRate(request.getManagementExpenseRate());
    }
    if (request.getFinanceExpenseRate() != null) {
      entity.setFinanceExpenseRate(request.getFinanceExpenseRate());
    }
    if (request.getSalesExpenseRate() != null) {
      entity.setSalesExpenseRate(request.getSalesExpenseRate());
    }
    if (request.getThreeExpenseRate2025() != null) {
      entity.setThreeExpenseRate2025(request.getThreeExpenseRate2025());
    }
    if (request.getThreeExpenseRate2026() != null) {
      entity.setThreeExpenseRate2026(request.getThreeExpenseRate2026());
    }
    if (request.getOverseasSales() != null) {
      entity.setOverseasSales(request.getOverseasSales());
    }
    if (request.getPeriod() != null) {
      entity.setPeriod(request.getPeriod());
    }
    if (request.getPeriodMonth() != null) {
      entity.setPeriodMonth(request.getPeriodMonth());
    }
    if (request.getPeriodYear() != null) {
      entity.setPeriodYear(request.getPeriodYear());
    }
    if (request.getStandardCompany() != null) {
      entity.setStandardCompany(request.getStandardCompany());
    }
    if (request.getProductionDivision() != null) {
      entity.setProductionDivision(request.getProductionDivision());
    }
    if (request.getApplicantDepartment() != null) {
      entity.setApplicantDepartment(request.getApplicantDepartment());
    }
    if (request.getApplicantOffice() != null) {
      entity.setApplicantOffice(request.getApplicantOffice());
    }
    if (request.getProductCategory() != null) {
      entity.setProductCategory(request.getProductCategory());
    }
    if (request.getProductLine() != null) {
      entity.setProductLine(request.getProductLine());
    }
    if (request.getThreeExpenseTotalRate() != null) {
      entity.setThreeExpenseTotalRate(request.getThreeExpenseTotalRate());
    }
    if (request.getOemExpenseRate() != null) {
      entity.setOemExpenseRate(request.getOemExpenseRate());
    }
    if (request.getSourceType() != null) {
      entity.setSourceType(request.getSourceType());
    }
    if (request.getImportBatchNo() != null) {
      entity.setImportBatchNo(request.getImportBatchNo());
    }
    if (request.getBusinessUnitType() != null) {
      entity.setBusinessUnitType(request.getBusinessUnitType());
    }
  }

  private void merge(ThreeExpenseRate target, ThreeExpenseRate source) {
    if (source.getCompany() != null) {
      target.setCompany(source.getCompany());
    }
    if (source.getBusinessUnit() != null) {
      target.setBusinessUnit(source.getBusinessUnit());
    }
    if (source.getDepartment() != null) {
      target.setDepartment(source.getDepartment());
    }
    if (source.getManagementExpenseRate() != null) {
      target.setManagementExpenseRate(source.getManagementExpenseRate());
    }
    if (source.getFinanceExpenseRate() != null) {
      target.setFinanceExpenseRate(source.getFinanceExpenseRate());
    }
    if (source.getSalesExpenseRate() != null) {
      target.setSalesExpenseRate(source.getSalesExpenseRate());
    }
    if (source.getThreeExpenseRate2025() != null) {
      target.setThreeExpenseRate2025(source.getThreeExpenseRate2025());
    }
    if (source.getThreeExpenseRate2026() != null) {
      target.setThreeExpenseRate2026(source.getThreeExpenseRate2026());
    }
    if (source.getOverseasSales() != null) {
      target.setOverseasSales(source.getOverseasSales());
    }
    if (source.getPeriod() != null) {
      target.setPeriod(source.getPeriod());
    }
    if (source.getPeriodMonth() != null) {
      target.setPeriodMonth(source.getPeriodMonth());
    }
    if (source.getPeriodYear() != null) {
      target.setPeriodYear(source.getPeriodYear());
    }
    if (source.getStandardCompany() != null) {
      target.setStandardCompany(source.getStandardCompany());
    }
    if (source.getProductionDivision() != null) {
      target.setProductionDivision(source.getProductionDivision());
    }
    if (source.getApplicantDepartment() != null) {
      target.setApplicantDepartment(source.getApplicantDepartment());
    }
    if (source.getApplicantOffice() != null) {
      target.setApplicantOffice(source.getApplicantOffice());
    }
    if (source.getProductCategory() != null) {
      target.setProductCategory(source.getProductCategory());
    }
    if (source.getProductLine() != null) {
      target.setProductLine(source.getProductLine());
    }
    if (source.getThreeExpenseTotalRate() != null) {
      target.setThreeExpenseTotalRate(source.getThreeExpenseTotalRate());
    }
    if (source.getOemExpenseRate() != null) {
      target.setOemExpenseRate(source.getOemExpenseRate());
    }
    if (source.getSourceType() != null) {
      target.setSourceType(source.getSourceType());
    }
    if (source.getImportBatchNo() != null) {
      target.setImportBatchNo(source.getImportBatchNo());
    }
    if (source.getBusinessUnitType() != null) {
      target.setBusinessUnitType(source.getBusinessUnitType());
    }
  }

  private void mergeImportValues(ThreeExpenseRate target, ThreeExpenseRate source) {
    // 标题拆出的 productCategory + productLine 是去重键的一部分，6 个配置区域不会互相覆盖。
    target.setManagementExpenseRate(source.getManagementExpenseRate());
    target.setFinanceExpenseRate(source.getFinanceExpenseRate());
    target.setSalesExpenseRate(source.getSalesExpenseRate());
    target.setThreeExpenseTotalRate(source.getThreeExpenseTotalRate());
    target.setOemExpenseRate(source.getOemExpenseRate());
    target.setPeriodYear(source.getPeriodYear());
    target.setPeriodMonth(source.getPeriodMonth());
    target.setPeriod(source.getPeriod());
    target.setSourceType(source.getSourceType());
    target.setImportBatchNo(source.getImportBatchNo());
    target.setCompany(source.getCompany());
    target.setBusinessUnit(source.getBusinessUnit());
    target.setDepartment(source.getDepartment());
    target.setStandardCompany(source.getStandardCompany());
    target.setProductionDivision(source.getProductionDivision());
    target.setOverseasSales(source.getOverseasSales());
    target.setThreeExpenseRate2025(source.getThreeExpenseRate2025());
    target.setThreeExpenseRate2026(source.getThreeExpenseRate2026());
  }

  private void fillDefaults(ThreeExpenseRate entity) {
    entity.setCompany(trimToNull(entity.getCompany()));
    entity.setBusinessUnit(trimToNull(entity.getBusinessUnit()));
    entity.setDepartment(trimToNull(entity.getDepartment()));
    entity.setOverseasSales(trimToNull(entity.getOverseasSales()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
    entity.setPeriodMonth(trimToNull(entity.getPeriodMonth()));
    entity.setStandardCompany(trimToNull(entity.getStandardCompany()));
    entity.setProductionDivision(trimToNull(entity.getProductionDivision()));
    entity.setApplicantDepartment(trimToNull(entity.getApplicantDepartment()));
    entity.setApplicantOffice(normalizeApplicantOffice(entity.getApplicantOffice()));
    // productCategory/productLine 来自配置表标题拆分，是新唯一键的核心匹配维度。
    entity.setProductCategory(trimToNull(entity.getProductCategory()));
    entity.setProductLine(trimToNull(entity.getProductLine()));
    entity.setSourceType(defaultIfBlank(entity.getSourceType(), "EXCEL_IMPORT"));
    entity.setImportBatchNo(trimToNull(entity.getImportBatchNo()));
    entity.setBusinessUnitType(defaultIfBlank(entity.getBusinessUnitType(), inferBusinessUnitType(entity)));
    if (entity.getPeriodYear() == null || entity.getPeriodYear() <= 0) {
      entity.setPeriodYear(resolvePeriodYear(entity));
    }
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String trimToEmpty(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.trim();
  }

  private String normalizeApplicantOffice(String value) {
    String text = trimToEmpty(value);
    return "/".equals(text) ? "" : text;
  }

  private String defaultIfBlank(String value, String defaultValue) {
    if (!StringUtils.hasText(value)) {
      return defaultValue;
    }
    return value.trim();
  }

  private String inferBusinessUnitType(ThreeExpenseRate entity) {
    return "家代商代销产品".equals(entity.getProductCategory()) ? "HOUSEHOLD" : "COMMERCIAL";
  }

  private boolean hasRequired(ThreeExpenseRate entity) {
    boolean hasExpenseRates = entity.getManagementExpenseRate() != null
        && entity.getFinanceExpenseRate() != null
        && entity.getSalesExpenseRate() != null;
    boolean hasLegacyIdentity = StringUtils.hasText(entity.getCompany())
        && StringUtils.hasText(entity.getBusinessUnit())
        && StringUtils.hasText(entity.getDepartment())
        && entity.getThreeExpenseRate2025() != null
        && entity.getThreeExpenseRate2026() != null
        && StringUtils.hasText(entity.getPeriod());
    boolean hasTitleMatrixIdentity = entity.getPeriodYear() != null
        && entity.getPeriodYear() > 0
        && StringUtils.hasText(entity.getProductCategory())
        && StringUtils.hasText(entity.getProductLine())
        && StringUtils.hasText(entity.getApplicantDepartment());
    return hasExpenseRates && (hasLegacyIdentity || hasTitleMatrixIdentity);
  }

  private ThreeExpenseRate findExisting(ThreeExpenseRate entity) {
    if (hasTitleMatrixIdentity(entity)) {
      return threeExpenseRateMapper.selectOne(
          Wrappers.lambdaQuery(ThreeExpenseRate.class)
              .eq(ThreeExpenseRate::getBusinessUnitType, entity.getBusinessUnitType())
              .eq(ThreeExpenseRate::getPeriodYear, entity.getPeriodYear())
              .eq(ThreeExpenseRate::getProductCategory, entity.getProductCategory())
              .eq(ThreeExpenseRate::getProductLine, entity.getProductLine())
              .eq(ThreeExpenseRate::getApplicantDepartment, entity.getApplicantDepartment())
              .eq(ThreeExpenseRate::getApplicantOffice, trimToEmpty(entity.getApplicantOffice()))
              .last("LIMIT 1"));
    }
    var query = Wrappers.lambdaQuery(ThreeExpenseRate.class)
        .eq(ThreeExpenseRate::getCompany, entity.getCompany())
        .eq(ThreeExpenseRate::getBusinessUnit, entity.getBusinessUnit())
        .eq(ThreeExpenseRate::getDepartment, entity.getDepartment());
    if (StringUtils.hasText(entity.getPeriod())) {
      query.eq(ThreeExpenseRate::getPeriod, entity.getPeriod());
    } else {
      query.isNull(ThreeExpenseRate::getPeriod);
    }
    return threeExpenseRateMapper.selectOne(query.last("LIMIT 1"));
  }

  private String importKey(ThreeExpenseRate entity) {
    if (hasTitleMatrixIdentity(entity)) {
      return String.join(
          "\u001F",
          entity.getBusinessUnitType(),
          String.valueOf(entity.getPeriodYear()),
          entity.getProductCategory(),
          entity.getProductLine(),
          entity.getApplicantDepartment(),
          trimToEmpty(entity.getApplicantOffice()));
    }
    return String.join(
        "\u001F",
        trimToEmpty(entity.getCompany()),
        trimToEmpty(entity.getBusinessUnit()),
        trimToEmpty(entity.getDepartment()),
        trimToEmpty(entity.getPeriod()));
  }

  private boolean hasTitleMatrixIdentity(ThreeExpenseRate entity) {
    return entity.getPeriodYear() != null
        && entity.getPeriodYear() > 0
        && StringUtils.hasText(entity.getProductCategory())
        && StringUtils.hasText(entity.getProductLine())
        && StringUtils.hasText(entity.getApplicantDepartment());
  }

  private Integer resolvePeriodYear(ThreeExpenseRate entity) {
    Integer year = parseLeadingYear(entity.getPeriodMonth());
    if (year != null) {
      return year;
    }
    return parseLeadingYear(entity.getPeriod());
  }

  private Integer parseLeadingYear(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String text = value.trim();
    if (text.length() < 4) {
      return null;
    }
    String yearText = text.substring(0, 4);
    for (int i = 0; i < yearText.length(); i++) {
      if (!Character.isDigit(yearText.charAt(i))) {
        return null;
      }
    }
    int year = Integer.parseInt(yearText);
    return year > 0 ? year : null;
  }
}
