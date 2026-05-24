package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.QualityLossRateImportRequest;
import com.sanhua.marketingcost.dto.QualityLossRateImportResponse;
import com.sanhua.marketingcost.dto.QualityLossRateRequest;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QualityLossRateService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QualityLossRateServiceImpl implements QualityLossRateService {
  private static final String MATCH_LEVEL_MATERIAL_CODE = "MATERIAL_CODE";
  private static final String MATCH_LEVEL_MATERIAL_MODEL = "MATERIAL_MODEL";
  private static final String DEFAULT_BUSINESS_UNIT_TYPE = "COMMERCIAL";
  /** company 是历史字段，新净损失率口径不再维护公司维度，但数据库仍要求非空。 */
  private static final String LEGACY_COMPANY_PLACEHOLDER = "";

  private final QualityLossRateMapper qualityLossRateMapper;

  public QualityLossRateServiceImpl(QualityLossRateMapper qualityLossRateMapper) {
    this.qualityLossRateMapper = qualityLossRateMapper;
  }

  @Override
  public Page<QualityLossRate> page(
      String company,
      String businessUnit,
      String productCategory,
      String productSubcategory,
      String customer,
      String period,
      Integer rateYear,
      String businessDivision,
      String productCode,
      String productName,
      String productModel,
      int page,
      int pageSize) {
    var query = Wrappers.lambdaQuery(QualityLossRate.class);
    if (StringUtils.hasText(company)) {
      query.like(QualityLossRate::getCompany, company.trim());
    }
    if (StringUtils.hasText(businessUnit)) {
      query.like(QualityLossRate::getBusinessUnit, businessUnit.trim());
    }
    if (StringUtils.hasText(productCategory)) {
      query.like(QualityLossRate::getProductCategory, productCategory.trim());
    }
    if (StringUtils.hasText(productSubcategory)) {
      query.like(QualityLossRate::getProductSubcategory, productSubcategory.trim());
    }
    if (StringUtils.hasText(customer)) {
      query.like(QualityLossRate::getCustomer, customer.trim());
    }
    if (StringUtils.hasText(period)) {
      query.eq(QualityLossRate::getPeriod, period.trim());
    }
    if (rateYear != null) {
      query.eq(QualityLossRate::getRateYear, rateYear);
    }
    if (StringUtils.hasText(businessDivision)) {
      String value = businessDivision.trim();
      query.and(
          q -> q.like(QualityLossRate::getBusinessDivision, value)
              .or()
              .like(QualityLossRate::getBusinessUnit, value));
    }
    if (StringUtils.hasText(productCode)) {
      query.like(QualityLossRate::getProductCode, productCode.trim());
    }
    if (StringUtils.hasText(productName)) {
      query.like(QualityLossRate::getProductName, productName.trim());
    }
    if (StringUtils.hasText(productModel)) {
      query.like(QualityLossRate::getProductModel, productModel.trim());
    }
    query.orderByDesc(QualityLossRate::getRateYear)
        .orderByDesc(QualityLossRate::getPeriod)
        .orderByDesc(QualityLossRate::getId);
    Page<QualityLossRate> pager = new Page<>(page, pageSize);
    return qualityLossRateMapper.selectPage(pager, query);
  }

  @Override
  @CacheEvict(value = "qualityLossRates", allEntries = true)
  public QualityLossRate create(QualityLossRateRequest request) {
    if (request == null) {
      return null;
    }
    QualityLossRate entity = new QualityLossRate();
    merge(entity, request);
    fillDefaults(entity, null);
    if (!hasRequired(entity)) {
      return null;
    }
    qualityLossRateMapper.insert(entity);
    return entity;
  }

  @Override
  @CacheEvict(value = "qualityLossRates", allEntries = true)
  public QualityLossRate update(Long id, QualityLossRateRequest request) {
    if (id == null) {
      return null;
    }
    QualityLossRate existing = qualityLossRateMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing, null);
    if (!hasRequired(existing)) {
      return null;
    }
    qualityLossRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "qualityLossRates", allEntries = true)
  public boolean delete(Long id) {
    return id != null && qualityLossRateMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "qualityLossRates", allEntries = true)
  public QualityLossRateImportResponse importItems(QualityLossRateImportRequest request) {
    QualityLossRateImportResponse response = new QualityLossRateImportResponse();
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
      QualityLossRate entity = new QualityLossRate();
      fillFromRow(entity, row, request);
      fillDefaults(entity, request);
      String validationError = validate(entity, rowNo);
      if (validationError != null) {
        response.addError(validationError);
        response.incrementSkipped();
        continue;
      }
      QualityLossRate existing = findExisting(entity);
      if (existing == null) {
        qualityLossRateMapper.insert(entity);
        response.incrementInserted();
        response.addRecord(entity);
      } else {
        merge(existing, entity);
        qualityLossRateMapper.updateById(existing);
        response.incrementUpdated();
        response.addRecord(existing);
      }
    }
    return response;
  }

  private void fillFromRow(
      QualityLossRate entity,
      QualityLossRateImportRequest.QualityLossRateRow row,
      QualityLossRateImportRequest request) {
    entity.setCompany(row.getCompany());
    entity.setBusinessUnit(row.getBusinessUnit());
    entity.setBusinessDivision(firstText(row.getBusinessDivision(), row.getBusinessUnit()));
    entity.setProductCategory(row.getProductCategory());
    entity.setProductSubcategory(row.getProductSubcategory());
    entity.setProductCode(row.getProductCode());
    entity.setProductName(row.getProductName());
    entity.setProductModel(row.getProductModel());
    entity.setProductSpec(row.getProductSpec());
    entity.setLossRate(row.getLossRate());
    entity.setCustomer(row.getCustomer());
    entity.setPeriod(row.getPeriod());
    entity.setRateYear(row.getRateYear() != null ? row.getRateYear() : request.getRateYear());
    entity.setSourceBasis(row.getSourceBasis());
    entity.setRemark(row.getRemark());
    entity.setBusinessUnitType(resolveBusinessUnitType(request.getBusinessUnitType()));
    entity.setSourceType("EXCEL_IMPORT");
    entity.setSourceBatchNo(request.getSourceBatchNo());
  }

  private void merge(QualityLossRate entity, QualityLossRateRequest request) {
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
    if (request.getProductModel() != null) {
      entity.setProductModel(request.getProductModel());
    }
    if (request.getProductSpec() != null) {
      entity.setProductSpec(request.getProductSpec());
    }
    if (request.getLossRate() != null) {
      entity.setLossRate(request.getLossRate());
    }
    if (request.getCustomer() != null) {
      entity.setCustomer(request.getCustomer());
    }
    if (request.getPeriod() != null) {
      entity.setPeriod(request.getPeriod());
    }
    if (request.getRateYear() != null) {
      entity.setRateYear(request.getRateYear());
    }
    if (request.getSourceBasis() != null) {
      entity.setSourceBasis(request.getSourceBasis());
    }
    if (request.getBusinessUnitType() != null) {
      entity.setBusinessUnitType(request.getBusinessUnitType());
    }
    if (request.getRemark() != null) {
      entity.setRemark(request.getRemark());
    }
    if (request.getSourceType() != null) {
      entity.setSourceType(request.getSourceType());
    }
    if (request.getSourceBatchNo() != null) {
      entity.setSourceBatchNo(request.getSourceBatchNo());
    }
  }

  private void merge(QualityLossRate target, QualityLossRate source) {
    target.setCompany(source.getCompany());
    target.setBusinessUnit(source.getBusinessUnit());
    target.setBusinessDivision(source.getBusinessDivision());
    target.setProductCategory(source.getProductCategory());
    target.setProductSubcategory(source.getProductSubcategory());
    target.setProductCode(source.getProductCode());
    target.setProductName(source.getProductName());
    target.setProductModel(source.getProductModel());
    target.setProductSpec(source.getProductSpec());
    target.setLossRate(source.getLossRate());
    target.setCustomer(source.getCustomer());
    target.setPeriod(source.getPeriod());
    target.setRateYear(source.getRateYear());
    target.setSourceBasis(source.getSourceBasis());
    target.setBusinessUnitType(source.getBusinessUnitType());
    target.setRemark(source.getRemark());
    target.setSourceType(source.getSourceType());
    target.setSourceBatchNo(source.getSourceBatchNo());
    target.setMatchLevel(source.getMatchLevel());
    target.setMatchKey(source.getMatchKey());
  }

  private void fillDefaults(QualityLossRate entity, QualityLossRateImportRequest request) {
    entity.setCompany(defaultCompany(entity.getCompany()));
    entity.setBusinessUnit(legacyRequiredText(firstText(entity.getBusinessUnit(), entity.getBusinessDivision())));
    entity.setBusinessDivision(trimToNull(firstText(entity.getBusinessDivision(), entity.getBusinessUnit())));
    entity.setProductCategory(legacyRequiredText(entity.getProductCategory()));
    entity.setProductSubcategory(legacyRequiredText(entity.getProductSubcategory()));
    entity.setProductCode(trimToNull(entity.getProductCode()));
    entity.setProductName(trimToNull(entity.getProductName()));
    entity.setProductModel(trimToNull(entity.getProductModel()));
    entity.setProductSpec(trimToNull(entity.getProductSpec()));
    entity.setCustomer(trimToNull(entity.getCustomer()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
    entity.setSourceBasis(trimToNull(entity.getSourceBasis()));
    entity.setBusinessUnitType(trimToNull(entity.getBusinessUnitType()));
    entity.setRemark(trimToNull(entity.getRemark()));
    entity.setSourceType(trimToNull(entity.getSourceType()));
    entity.setSourceBatchNo(trimToNull(entity.getSourceBatchNo()));
    if (entity.getRateYear() == null) {
      entity.setRateYear(resolveYear(entity.getPeriod()));
    }
    if (!StringUtils.hasText(entity.getPeriod()) && entity.getRateYear() != null) {
      entity.setPeriod(entity.getRateYear() + "-01");
    }
    if (!StringUtils.hasText(entity.getSourceType())) {
      entity.setSourceType(request == null ? "MANUAL" : "EXCEL_IMPORT");
    }
    deriveMatchKey(entity);
  }

  private void deriveMatchKey(QualityLossRate entity) {
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
    entity.setMatchLevel(null);
    entity.setMatchKey(null);
  }

  private String validate(QualityLossRate entity, int rowNo) {
    if (entity.getRateYear() == null) {
      return "Excel第" + rowNo + "行缺年度";
    }
    if (entity.getLossRate() == null) {
      return "Excel第" + rowNo + "行缺报价净损失率";
    }
    if (!StringUtils.hasText(entity.getProductCode())
        && !StringUtils.hasText(entity.getProductModel())) {
      return "Excel第" + rowNo + "行缺产品料号或产品型号";
    }
    return null;
  }

  private boolean hasRequired(QualityLossRate entity) {
    return entity.getRateYear() != null
        && entity.getLossRate() != null
        && StringUtils.hasText(entity.getMatchLevel())
        && StringUtils.hasText(entity.getMatchKey());
  }

  private QualityLossRate findExisting(QualityLossRate entity) {
    var query = Wrappers.lambdaQuery(QualityLossRate.class)
        .eq(QualityLossRate::getRateYear, entity.getRateYear())
        .eq(QualityLossRate::getMatchLevel, entity.getMatchLevel())
        .eq(QualityLossRate::getMatchKey, entity.getMatchKey());
    if (StringUtils.hasText(entity.getBusinessUnitType())) {
      query.eq(QualityLossRate::getBusinessUnitType, entity.getBusinessUnitType());
    } else {
      query.isNull(QualityLossRate::getBusinessUnitType);
    }
    return qualityLossRateMapper.selectOne(query.last("LIMIT 1"));
  }

  private boolean isBlankRow(QualityLossRateImportRequest.QualityLossRateRow row) {
    return !StringUtils.hasText(row.getBusinessDivision())
        && !StringUtils.hasText(row.getBusinessUnit())
        && !StringUtils.hasText(row.getProductCategory())
        && !StringUtils.hasText(row.getProductCode())
        && !StringUtils.hasText(row.getProductName())
        && !StringUtils.hasText(row.getProductModel())
        && !StringUtils.hasText(row.getProductSpec())
        && row.getLossRate() == null
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

  private String defaultCompany(String company) {
    String value = trimToNull(company);
    return value == null ? LEGACY_COMPANY_PLACEHOLDER : value;
  }

  private String legacyRequiredText(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? "" : trimmed;
  }
}
