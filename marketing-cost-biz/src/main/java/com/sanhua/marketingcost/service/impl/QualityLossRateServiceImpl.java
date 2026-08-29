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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QualityLossRateServiceImpl implements QualityLossRateService {
  private static final String DEFAULT_BUSINESS_UNIT_TYPE = "COMMERCIAL";
  private static final int IMPORT_BATCH_SIZE = 500;
  private static final BigDecimal MAX_RATE_EXCLUSIVE = BigDecimal.ONE;

  private final QualityLossRateMapper qualityLossRateMapper;

  public QualityLossRateServiceImpl(QualityLossRateMapper qualityLossRateMapper) {
    this.qualityLossRateMapper = qualityLossRateMapper;
  }

  @Override
  public Page<QualityLossRate> page(
      String productCategory,
      String productSubcategory,
      Integer rateYear,
      String businessDivision,
      String bareProductCode,
      String productName,
      String productModel,
      int page,
      int pageSize) {
    var query = Wrappers.lambdaQuery(QualityLossRate.class)
        .eq(QualityLossRate::getBusinessUnitType, resolveBusinessUnitType(null));
    if (StringUtils.hasText(productCategory)) {
      query.like(QualityLossRate::getProductCategory, productCategory.trim());
    }
    if (StringUtils.hasText(productSubcategory)) {
      query.like(QualityLossRate::getProductSubcategory, productSubcategory.trim());
    }
    if (rateYear != null) {
      query.eq(QualityLossRate::getRateYear, rateYear);
    }
    if (StringUtils.hasText(businessDivision)) {
      query.like(QualityLossRate::getBusinessDivision, businessDivision.trim());
    }
    if (StringUtils.hasText(bareProductCode)) {
      query.like(QualityLossRate::getBareProductCode, bareProductCode.trim());
    }
    if (StringUtils.hasText(productName)) {
      query.like(QualityLossRate::getProductName, productName.trim());
    }
    if (StringUtils.hasText(productModel)) {
      query.like(QualityLossRate::getProductModel, productModel.trim());
    }
    query.orderByDesc(QualityLossRate::getRateYear)
        .orderByAsc(QualityLossRate::getBareProductCode);
    return qualityLossRateMapper.selectPage(new Page<>(page, pageSize), query);
  }

  @Override
  @CacheEvict(value = "qualityLossRates", allEntries = true)
  public QualityLossRate create(QualityLossRateRequest request) {
    if (request == null) {
      return null;
    }
    QualityLossRate entity = new QualityLossRate();
    merge(entity, request);
    normalize(entity);
    if (!isValid(entity) || findExisting(entity) != null) {
      return null;
    }
    qualityLossRateMapper.insert(entity);
    return entity;
  }

  @Override
  @CacheEvict(value = "qualityLossRates", allEntries = true)
  public QualityLossRate update(Long id, QualityLossRateRequest request) {
    if (id == null || request == null) {
      return null;
    }
    QualityLossRate existing = qualityLossRateMapper.selectById(id);
    if (existing == null
        || !resolveBusinessUnitType(null).equals(existing.getBusinessUnitType())) {
      return null;
    }
    merge(existing, request);
    normalize(existing);
    if (!isValid(existing)) {
      return null;
    }
    QualityLossRate duplicate = findExisting(existing);
    if (duplicate != null && !duplicate.getId().equals(existing.getId())) {
      return null;
    }
    qualityLossRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "qualityLossRates", allEntries = true)
  public boolean delete(Long id) {
    if (id == null) {
      return false;
    }
    return qualityLossRateMapper.delete(
        Wrappers.lambdaQuery(QualityLossRate.class)
            .eq(QualityLossRate::getId, id)
            .eq(QualityLossRate::getBusinessUnitType, resolveBusinessUnitType(null))) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "qualityLossRates", allEntries = true)
  public QualityLossRateImportResponse importItems(QualityLossRateImportRequest request) {
    QualityLossRateImportResponse response = new QualityLossRateImportResponse();
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return response;
    }
    String businessUnitType = resolveBusinessUnitType(null);
    String sourceBatchNo = resolveBatchNo(request.getSourceBatchNo());
    response.setSourceBatchNo(sourceBatchNo);
    List<QualityLossRate> validRows = new ArrayList<>(request.getRows().size());
    Set<String> importKeys = new HashSet<>();
    int index = 1;
    for (QualityLossRateImportRequest.QualityLossRateRow row : request.getRows()) {
      int rowNo = row != null && row.getRowNo() != null ? row.getRowNo() : index;
      index++;
      if (row == null || isBlankRow(row)) {
        response.incrementSkipped();
        continue;
      }
      if (row.getLossRate() == null) {
        response.incrementSkipped();
        continue;
      }
      QualityLossRate entity = fromImportRow(
          row, request.getRateYear(), businessUnitType, sourceBatchNo);
      String validationError = validate(entity, rowNo);
      if (validationError != null) {
        response.addError(validationError);
        response.incrementSkipped();
        continue;
      }
      String key = entity.getRateYear() + "\u0000" + entity.getBareProductCode();
      if (!importKeys.add(key)) {
        response.addError("Excel第" + rowNo + "行裸品料号重复：" + entity.getBareProductCode());
        response.incrementSkipped();
        continue;
      }
      validRows.add(entity);
    }
    for (int start = 0; start < validRows.size(); start += IMPORT_BATCH_SIZE) {
      List<QualityLossRate> batch =
          validRows.subList(start, Math.min(start + IMPORT_BATCH_SIZE, validRows.size()));
      Set<String> existingCodes = selectExistingCodes(batch, businessUnitType);
      for (QualityLossRate row : batch) {
        if (existingCodes.contains(row.getBareProductCode())) {
          response.incrementUpdated();
        } else {
          response.incrementInserted();
        }
      }
      qualityLossRateMapper.upsertBatch(batch);
    }
    return response;
  }

  private Set<String> selectExistingCodes(
      List<QualityLossRate> batch, String businessUnitType) {
    if (batch.isEmpty()) {
      return Set.of();
    }
    Integer year = batch.get(0).getRateYear();
    List<String> codes = batch.stream().map(QualityLossRate::getBareProductCode).toList();
    List<QualityLossRate> existing = qualityLossRateMapper.selectList(
        Wrappers.lambdaQuery(QualityLossRate.class)
            .eq(QualityLossRate::getBusinessUnitType, businessUnitType)
            .eq(QualityLossRate::getRateYear, year)
            .in(QualityLossRate::getBareProductCode, codes));
    Set<String> result = new HashSet<>();
    for (QualityLossRate row : existing) {
      result.add(row.getBareProductCode());
    }
    return result;
  }

  private QualityLossRate fromImportRow(
      QualityLossRateImportRequest.QualityLossRateRow row,
      Integer rateYear,
      String businessUnitType,
      String sourceBatchNo) {
    QualityLossRate entity = new QualityLossRate();
    entity.setBusinessUnitType(businessUnitType);
    entity.setRateYear(rateYear);
    entity.setBareProductCode(row.getBareProductCode());
    entity.setProductName(row.getProductName());
    entity.setMaterialSpec(row.getMaterialSpec());
    entity.setProductModel(row.getProductModel());
    entity.setBusinessDivision(row.getBusinessDivision());
    entity.setProductCategory(row.getProductCategory());
    entity.setProductSubcategory(row.getProductSubcategory());
    entity.setCategorySpec(row.getCategorySpec());
    entity.setFourthLevel(row.getFourthLevel());
    entity.setLossRate(row.getLossRate());
    entity.setRemark(row.getRemark());
    entity.setSourceType("EXCEL_IMPORT");
    entity.setSourceBatchNo(sourceBatchNo);
    normalize(entity);
    return entity;
  }

  private void merge(QualityLossRate entity, QualityLossRateRequest request) {
    if (request.getRateYear() != null) entity.setRateYear(request.getRateYear());
    if (request.getBareProductCode() != null) entity.setBareProductCode(request.getBareProductCode());
    if (request.getProductName() != null) entity.setProductName(request.getProductName());
    if (request.getMaterialSpec() != null) entity.setMaterialSpec(request.getMaterialSpec());
    if (request.getProductModel() != null) entity.setProductModel(request.getProductModel());
    if (request.getBusinessDivision() != null) entity.setBusinessDivision(request.getBusinessDivision());
    if (request.getProductCategory() != null) entity.setProductCategory(request.getProductCategory());
    if (request.getProductSubcategory() != null) entity.setProductSubcategory(request.getProductSubcategory());
    if (request.getCategorySpec() != null) entity.setCategorySpec(request.getCategorySpec());
    if (request.getFourthLevel() != null) entity.setFourthLevel(request.getFourthLevel());
    if (request.getLossRate() != null) entity.setLossRate(request.getLossRate());
    if (request.getRemark() != null) entity.setRemark(request.getRemark());
    entity.setBusinessUnitType(resolveBusinessUnitType(null));
    entity.setSourceType("MANUAL");
    entity.setSourceBatchNo(null);
  }

  private void normalize(QualityLossRate entity) {
    entity.setBusinessUnitType(resolveBusinessUnitType(entity.getBusinessUnitType()));
    entity.setBareProductCode(trimToNull(entity.getBareProductCode()));
    entity.setProductName(trimToNull(entity.getProductName()));
    entity.setMaterialSpec(trimToNull(entity.getMaterialSpec()));
    entity.setProductModel(trimToNull(entity.getProductModel()));
    entity.setBusinessDivision(trimToNull(entity.getBusinessDivision()));
    entity.setProductCategory(trimToNull(entity.getProductCategory()));
    entity.setProductSubcategory(trimToNull(entity.getProductSubcategory()));
    entity.setCategorySpec(trimToNull(entity.getCategorySpec()));
    entity.setFourthLevel(trimToNull(entity.getFourthLevel()));
    entity.setRemark(trimToNull(entity.getRemark()));
    entity.setSourceType(trimToNull(entity.getSourceType()));
    entity.setSourceBatchNo(trimToNull(entity.getSourceBatchNo()));
  }

  private String validate(QualityLossRate entity, int rowNo) {
    if (entity.getRateYear() == null) {
      return "Excel第" + rowNo + "行缺年度";
    }
    if (!StringUtils.hasText(entity.getBareProductCode())) {
      return "Excel第" + rowNo + "行缺裸品料号";
    }
    if (!isRateValid(entity.getLossRate())) {
      return "Excel第" + rowNo + "行净损失率必须大于等于0且小于1";
    }
    return null;
  }

  private boolean isValid(QualityLossRate entity) {
    return entity.getRateYear() != null
        && StringUtils.hasText(entity.getBareProductCode())
        && isRateValid(entity.getLossRate());
  }

  private boolean isRateValid(BigDecimal value) {
    return value != null
        && value.signum() >= 0
        && value.compareTo(MAX_RATE_EXCLUSIVE) < 0;
  }

  private QualityLossRate findExisting(QualityLossRate entity) {
    return qualityLossRateMapper.selectOne(
        Wrappers.lambdaQuery(QualityLossRate.class)
            .eq(QualityLossRate::getBusinessUnitType, entity.getBusinessUnitType())
            .eq(QualityLossRate::getRateYear, entity.getRateYear())
            .eq(QualityLossRate::getBareProductCode, entity.getBareProductCode())
            .last("LIMIT 1"));
  }

  private boolean isBlankRow(QualityLossRateImportRequest.QualityLossRateRow row) {
    return !StringUtils.hasText(row.getBareProductCode())
        && !StringUtils.hasText(row.getProductName())
        && !StringUtils.hasText(row.getMaterialSpec())
        && !StringUtils.hasText(row.getProductModel())
        && !StringUtils.hasText(row.getBusinessDivision())
        && !StringUtils.hasText(row.getProductCategory())
        && !StringUtils.hasText(row.getProductSubcategory())
        && !StringUtils.hasText(row.getCategorySpec())
        && !StringUtils.hasText(row.getFourthLevel())
        && row.getLossRate() == null
        && !StringUtils.hasText(row.getRemark());
  }

  private String resolveBusinessUnitType(String fallback) {
    String current = trimToNull(BusinessUnitContext.getCurrentBusinessUnitType());
    String value = current == null ? trimToNull(fallback) : current;
    return value == null ? DEFAULT_BUSINESS_UNIT_TYPE : value;
  }

  private String resolveBatchNo(String requested) {
    String value = trimToNull(requested);
    if (value != null) {
      return value;
    }
    return "QUALITY_LOSS_"
        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        + "_"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
