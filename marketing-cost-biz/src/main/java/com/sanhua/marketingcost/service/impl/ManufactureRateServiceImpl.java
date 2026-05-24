package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ManufactureRateImportRequest;
import com.sanhua.marketingcost.dto.ManufactureRateImportResponse;
import com.sanhua.marketingcost.dto.ManufactureRateRequest;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.ManufactureRateService;
import java.time.Year;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ManufactureRateServiceImpl implements ManufactureRateService {
  private static final String MATCH_LEVEL_MATERIAL_CODE = "MATERIAL_CODE";
  private static final String MATCH_LEVEL_MATERIAL_MODEL = "MATERIAL_MODEL";
  private static final String MATCH_LEVEL_DIVISION_PRODUCT_NAME = "DIVISION_PRODUCT_NAME";
  private static final String MATCH_LEVEL_DIVISION = "DIVISION";
  private static final String DEFAULT_BUSINESS_UNIT_TYPE = "COMMERCIAL";
  private static final String LEGACY_REQUIRED_PLACEHOLDER = "";
  private static final String MATCH_KEY_SEPARATOR = "::";

  private final ManufactureRateMapper manufactureRateMapper;

  public ManufactureRateServiceImpl(ManufactureRateMapper manufactureRateMapper) {
    this.manufactureRateMapper = manufactureRateMapper;
  }

  @Override
  @Cacheable(value = "manufactureRates", key = "#businessUnit ?: 'ALL'")
  public List<ManufactureRate> list(String businessUnit) {
    var query = Wrappers.lambdaQuery(ManufactureRate.class);
    if (StringUtils.hasText(businessUnit)) {
      String value = businessUnit.trim();
      query.and(
          q -> q.like(ManufactureRate::getBusinessDivision, value)
              .or()
              .like(ManufactureRate::getBusinessUnit, value));
    }
    query.orderByDesc(ManufactureRate::getRateYear)
        .orderByDesc(ManufactureRate::getPeriod)
        .orderByDesc(ManufactureRate::getId);
    return manufactureRateMapper.selectList(query);
  }

  @Override
  @CacheEvict(value = "manufactureRates", allEntries = true)
  public ManufactureRate create(ManufactureRateRequest request) {
    if (request == null) {
      return null;
    }
    ManufactureRate entity = new ManufactureRate();
    merge(entity, request);
    fillDefaults(entity, null);
    if (!hasRequired(entity)) {
      return null;
    }
    ManufactureRate existing = findExisting(entity);
    if (existing == null) {
      manufactureRateMapper.insert(entity);
      return entity;
    }
    merge(existing, entity);
    manufactureRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "manufactureRates", allEntries = true)
  public ManufactureRate update(Long id, ManufactureRateRequest request) {
    if (id == null) {
      return null;
    }
    ManufactureRate existing = manufactureRateMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing, null);
    if (!hasRequired(existing)) {
      return null;
    }
    manufactureRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "manufactureRates", allEntries = true)
  public boolean delete(Long id) {
    return id != null && manufactureRateMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "manufactureRates", allEntries = true)
  public ManufactureRateImportResponse importItems(ManufactureRateImportRequest request) {
    ManufactureRateImportResponse response = new ManufactureRateImportResponse();
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
      ManufactureRate entity = new ManufactureRate();
      fillFromRow(entity, row, request);
      fillDefaults(entity, request);
      String validationError = validate(entity, rowNo);
      if (validationError != null) {
        response.addError(validationError);
        response.incrementSkipped();
        continue;
      }
      ManufactureRate existing = findExisting(entity);
      if (existing == null) {
        manufactureRateMapper.insert(entity);
        response.incrementInserted();
        response.addRecord(entity);
      } else {
        merge(existing, entity);
        manufactureRateMapper.updateById(existing);
        response.incrementUpdated();
        response.addRecord(existing);
      }
    }
    return response;
  }

  private void fillFromRow(
      ManufactureRate entity,
      ManufactureRateImportRequest.ManufactureRateRow row,
      ManufactureRateImportRequest request) {
    entity.setCompany(row.getCompany());
    entity.setBusinessUnit(row.getBusinessUnit());
    entity.setBusinessDivision(firstText(row.getBusinessDivision(), row.getBusinessUnit()));
    entity.setProductCategory(row.getProductCategory());
    entity.setProductSubcategory(row.getProductSubcategory());
    entity.setProductCode(row.getProductCode());
    entity.setProductName(row.getProductName());
    entity.setProductSpec(row.getProductSpec());
    entity.setProductModel(row.getProductModel());
    entity.setFeeRate(row.getFeeRate());
    entity.setPeriod(row.getPeriod());
    entity.setRateYear(row.getRateYear() != null ? row.getRateYear() : request.getRateYear());
    entity.setRemark(row.getRemark());
    entity.setBusinessUnitType(resolveBusinessUnitType(request.getBusinessUnitType()));
    entity.setSourceType("EXCEL_IMPORT");
    entity.setSourceBatchNo(request.getSourceBatchNo());
  }

  private void merge(ManufactureRate entity, ManufactureRateRequest request) {
    if (request == null) {
      return;
    }
    if (request.getCompany() != null) {
      entity.setCompany(request.getCompany());
    }
    if (request.getBusinessUnit() != null) {
      entity.setBusinessUnit(request.getBusinessUnit());
    }
    if (request.getBusinessDivision() != null) {
      entity.setBusinessDivision(request.getBusinessDivision());
    }
    if (request.getProductCategory() != null) {
      entity.setProductCategory(request.getProductCategory());
    }
    if (request.getProductSubcategory() != null) {
      entity.setProductSubcategory(request.getProductSubcategory());
    }
    if (request.getProductCode() != null) {
      entity.setProductCode(request.getProductCode());
    }
    if (request.getProductName() != null) {
      entity.setProductName(request.getProductName());
    }
    if (request.getProductSpec() != null) {
      entity.setProductSpec(request.getProductSpec());
    }
    if (request.getProductModel() != null) {
      entity.setProductModel(request.getProductModel());
    }
    if (request.getFeeRate() != null) {
      entity.setFeeRate(request.getFeeRate());
    }
    if (request.getPeriod() != null) {
      entity.setPeriod(request.getPeriod());
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
  }

  private void merge(ManufactureRate target, ManufactureRate source) {
    target.setCompany(source.getCompany());
    target.setBusinessUnit(source.getBusinessUnit());
    target.setBusinessDivision(source.getBusinessDivision());
    target.setProductCategory(source.getProductCategory());
    target.setProductSubcategory(source.getProductSubcategory());
    target.setProductCode(source.getProductCode());
    target.setProductName(source.getProductName());
    target.setProductSpec(source.getProductSpec());
    target.setProductModel(source.getProductModel());
    target.setFeeRate(source.getFeeRate());
    target.setPeriod(source.getPeriod());
    target.setRateYear(source.getRateYear());
    target.setBusinessUnitType(source.getBusinessUnitType());
    target.setRemark(source.getRemark());
    target.setSourceType(source.getSourceType());
    target.setSourceBatchNo(source.getSourceBatchNo());
    target.setMatchLevel(source.getMatchLevel());
    target.setMatchKey(source.getMatchKey());
  }

  private void fillDefaults(ManufactureRate entity, ManufactureRateImportRequest request) {
    entity.setCompany(legacyRequiredText(entity.getCompany()));
    entity.setBusinessDivision(trimToNull(firstText(entity.getBusinessDivision(), entity.getBusinessUnit())));
    entity.setBusinessUnit(legacyRequiredText(firstText(entity.getBusinessUnit(), entity.getBusinessDivision())));
    entity.setProductCategory(legacyRequiredText(entity.getProductCategory()));
    entity.setProductSubcategory(legacyRequiredText(entity.getProductSubcategory()));
    entity.setProductCode(trimToNull(entity.getProductCode()));
    entity.setProductName(trimToNull(entity.getProductName()));
    entity.setProductSpec(trimToNull(entity.getProductSpec()));
    entity.setProductModel(trimToNull(entity.getProductModel()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
    entity.setBusinessUnitType(resolveBusinessUnitType(entity.getBusinessUnitType()));
    entity.setRemark(trimToNull(entity.getRemark()));
    entity.setSourceType(trimToNull(entity.getSourceType()));
    entity.setSourceBatchNo(trimToNull(entity.getSourceBatchNo()));
    if (entity.getRateYear() == null) {
      entity.setRateYear(resolveYear(entity.getPeriod()));
    }
    if (entity.getRateYear() == null) {
      entity.setRateYear(Year.now().getValue());
    }
    if (!StringUtils.hasText(entity.getPeriod()) && entity.getRateYear() != null) {
      entity.setPeriod(entity.getRateYear() + "-01");
    }
    if (!StringUtils.hasText(entity.getSourceType())) {
      entity.setSourceType(request == null ? "MANUAL" : "EXCEL_IMPORT");
    }
    deriveMatchKey(entity);
  }

  private void deriveMatchKey(ManufactureRate entity) {
    if (StringUtils.hasText(entity.getProductCode())) {
      entity.setMatchLevel(MATCH_LEVEL_MATERIAL_CODE);
      entity.setMatchKey(entity.getProductCode());
      return;
    }
    if (StringUtils.hasText(entity.getProductModel())) {
      entity.setMatchLevel(MATCH_LEVEL_MATERIAL_MODEL);
      entity.setMatchKey(entity.getProductModel());
      return;
    }
    if (StringUtils.hasText(entity.getBusinessDivision())
        && StringUtils.hasText(entity.getProductName())) {
      entity.setMatchLevel(MATCH_LEVEL_DIVISION_PRODUCT_NAME);
      entity.setMatchKey(entity.getBusinessDivision() + MATCH_KEY_SEPARATOR + entity.getProductName());
      return;
    }
    if (StringUtils.hasText(entity.getBusinessDivision())) {
      entity.setMatchLevel(MATCH_LEVEL_DIVISION);
      entity.setMatchKey(entity.getBusinessDivision());
      return;
    }
    entity.setMatchLevel(null);
    entity.setMatchKey(null);
  }

  private String validate(ManufactureRate entity, int rowNo) {
    if (entity.getRateYear() == null) {
      return "Excel第" + rowNo + "行缺年度";
    }
    if (entity.getFeeRate() == null) {
      return "Excel第" + rowNo + "行缺制造费用率";
    }
    if (!StringUtils.hasText(entity.getMatchLevel())
        || !StringUtils.hasText(entity.getMatchKey())) {
      return "Excel第" + rowNo + "行缺料号、产品型号、产品名称+事业部或事业部";
    }
    return null;
  }

  private boolean hasRequired(ManufactureRate entity) {
    return entity.getRateYear() != null
        && entity.getFeeRate() != null
        && StringUtils.hasText(entity.getMatchLevel())
        && StringUtils.hasText(entity.getMatchKey());
  }

  private ManufactureRate findExisting(ManufactureRate entity) {
    var query = Wrappers.lambdaQuery(ManufactureRate.class)
        .eq(ManufactureRate::getRateYear, entity.getRateYear())
        .eq(ManufactureRate::getMatchLevel, entity.getMatchLevel())
        .eq(ManufactureRate::getMatchKey, entity.getMatchKey());
    if (StringUtils.hasText(entity.getBusinessUnitType())) {
      query.eq(ManufactureRate::getBusinessUnitType, entity.getBusinessUnitType());
    } else {
      query.isNull(ManufactureRate::getBusinessUnitType);
    }
    return manufactureRateMapper.selectOne(query.last("LIMIT 1"));
  }

  private boolean isBlankRow(ManufactureRateImportRequest.ManufactureRateRow row) {
    return !StringUtils.hasText(row.getBusinessDivision())
        && !StringUtils.hasText(row.getBusinessUnit())
        && !StringUtils.hasText(row.getProductCode())
        && !StringUtils.hasText(row.getProductName())
        && !StringUtils.hasText(row.getProductModel())
        && !StringUtils.hasText(row.getProductSpec())
        && row.getFeeRate() == null
        && !StringUtils.hasText(row.getRemark());
  }

  private Integer resolveYear(String period) {
    if (!StringUtils.hasText(period) || period.trim().length() < 4) {
      return null;
    }
    try {
      return Integer.valueOf(period.trim().substring(0, 4));
    } catch (NumberFormatException ex) {
      return null;
    }
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

  private String resolveBusinessUnitType(String requestValue) {
    String value = firstText(requestValue, BusinessUnitContext.getCurrentBusinessUnitType());
    return StringUtils.hasText(value) ? value : DEFAULT_BUSINESS_UNIT_TYPE;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String legacyRequiredText(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? LEGACY_REQUIRED_PLACEHOLDER : trimmed;
  }
}
