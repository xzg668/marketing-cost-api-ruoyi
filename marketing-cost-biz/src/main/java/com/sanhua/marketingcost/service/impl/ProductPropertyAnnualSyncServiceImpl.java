package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRequest;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRow;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.ProductPropertyAnnualSyncService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductPropertyAnnualSyncServiceImpl implements ProductPropertyAnnualSyncService {
  private static final String DEFAULT_BUSINESS_UNIT_TYPE = "COMMERCIAL";
  private static final String PLACEHOLDER_LEVEL1_CODE = "OA_PLACEHOLDER";
  private static final String PLACEHOLDER_TEXT = "待维护";
  private static final BigDecimal STANDARD_COEFFICIENT = new BigDecimal("1.0000");
  private static final BigDecimal NON_STANDARD_COEFFICIENT = new BigDecimal("1.0500");
  private static final BigDecimal HIGH_ANNUAL_USAGE_THRESHOLD = new BigDecimal("100000");

  private final ProductPropertyMapper productPropertyMapper;

  public ProductPropertyAnnualSyncServiceImpl(ProductPropertyMapper productPropertyMapper) {
    this.productPropertyMapper = productPropertyMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ProductPropertyAnnualSyncResult sync(ProductPropertyAnnualSyncRequest request) {
    ProductPropertyAnnualSyncResult result = new ProductPropertyAnnualSyncResult();
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return result;
    }
    for (ProductPropertyAnnualSyncRow row : request.getRows()) {
      syncOne(request, row, result);
    }
    return result;
  }

  private void syncOne(
      ProductPropertyAnnualSyncRequest request,
      ProductPropertyAnnualSyncRow row,
      ProductPropertyAnnualSyncResult result) {
    if (row == null) {
      result.incrementSkipped();
      return;
    }
    ProductProperty incoming = toEntity(request, row);
    normalize(request, incoming);
    String rowLabel = row.getRowNo() == null ? "当前行" : "第" + row.getRowNo() + "行";
    if (!validate(request, incoming, rowLabel, result)) {
      return;
    }

    ProductProperty existing = findExisting(request, incoming);
    if (incoming.getId() != null && existing == null) {
      result.addError(rowLabel + "产品属性记录不存在：" + incoming.getId());
      return;
    }
    if (existing == null && request.isUsageOnly() && !request.isCreatePlaceholderOnMissing()) {
      result.incrementSkipped();
      result.addWarning(rowLabel + "未找到年度产品属性记录，已跳过年用量更新");
      return;
    }

    if (existing == null) {
      prepareForInsert(request, incoming);
      productPropertyMapper.insert(incoming);
      result.incrementInserted();
      countRisk(incoming, result);
      result.addRecord(incoming);
      return;
    }

    boolean riskBefore = Integer.valueOf(1).equals(existing.getMatchRiskFlag());
    mergeExisting(existing, incoming, request.isUsageOnly());
    normalize(request, existing);
    productPropertyMapper.updateById(existing);
    result.incrementUpdated();
    if (!riskBefore) {
      countRisk(existing, result);
    }
    result.addRecord(existing);
  }

  private ProductProperty toEntity(
      ProductPropertyAnnualSyncRequest request, ProductPropertyAnnualSyncRow row) {
    ProductProperty entity = new ProductProperty();
    entity.setId(row.getId());
    entity.setBusinessUnitType(firstText(row.getBusinessUnitType(), request.getBusinessUnitType()));
    entity.setLevel1Code(row.getLevel1Code());
    entity.setLevel1Name(row.getLevel1Name());
    entity.setParentCode(row.getParentCode());
    entity.setParentName(row.getParentName());
    entity.setParentSpec(row.getParentSpec());
    entity.setParentModel(row.getParentModel());
    entity.setPeriod(row.getPeriod());
    entity.setProductAttr(row.getProductAttr());
    entity.setPropertyYear(row.getPropertyYear() == null ? request.getPropertyYear() : row.getPropertyYear());
    entity.setBusinessDivision(row.getBusinessDivision());
    entity.setProductCode(row.getProductCode());
    entity.setProductName(row.getProductName());
    entity.setProductModel(row.getProductModel());
    entity.setProductSpec(row.getProductSpec());
    entity.setAnnualUsage(row.getAnnualUsage());
    entity.setRemark(row.getRemark());
    entity.setAttrSourceType(firstText(row.getAttrSourceType(), request.getAttrSourceType()));
    entity.setAttrSourceBatchNo(firstText(row.getAttrSourceBatchNo(), request.getAttrSourceBatchNo()));
    entity.setAnnualUsageSourceType(
        firstText(row.getAnnualUsageSourceType(), request.getAnnualUsageSourceType()));
    entity.setAnnualUsageSourceBatchNo(
        firstText(row.getAnnualUsageSourceBatchNo(), request.getAnnualUsageSourceBatchNo()));
    entity.setAnnualUsageOaNo(row.getAnnualUsageOaNo());
    entity.setAnnualUsageOaLineId(row.getAnnualUsageOaLineId());
    entity.setEffectiveFrom(row.getEffectiveFrom());
    entity.setEffectiveTo(row.getEffectiveTo());
    entity.setMatchRiskFlag(row.getMatchRiskFlag());
    entity.setMatchRiskReason(row.getMatchRiskReason());
    entity.setCoefficient(row.getCoefficient());
    return entity;
  }

  private void normalize(ProductPropertyAnnualSyncRequest request, ProductProperty entity) {
    entity.setBusinessUnitType(trimToNull(entity.getBusinessUnitType()));
    if (!StringUtils.hasText(entity.getBusinessUnitType())) {
      entity.setBusinessUnitType(firstText(BusinessUnitContext.getCurrentBusinessUnitType(), DEFAULT_BUSINESS_UNIT_TYPE));
    }
    entity.setLevel1Code(trimToNull(entity.getLevel1Code()));
    entity.setLevel1Name(trimToNull(entity.getLevel1Name()));
    entity.setParentCode(trimToNull(entity.getParentCode()));
    entity.setParentName(trimToNull(entity.getParentName()));
    entity.setParentSpec(trimToNull(entity.getParentSpec()));
    entity.setParentModel(trimToNull(entity.getParentModel()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
    entity.setProductAttr(trimToNull(entity.getProductAttr()));
    entity.setBusinessDivision(trimToNull(entity.getBusinessDivision()));
    entity.setProductCode(trimToNull(entity.getProductCode()));
    entity.setProductName(trimToNull(entity.getProductName()));
    entity.setProductModel(trimToNull(entity.getProductModel()));
    entity.setProductSpec(trimToNull(entity.getProductSpec()));
    entity.setRemark(trimToNull(entity.getRemark()));
    entity.setAttrSourceType(trimToNull(entity.getAttrSourceType()));
    entity.setAttrSourceBatchNo(trimToNull(entity.getAttrSourceBatchNo()));
    entity.setAnnualUsageSourceType(trimToNull(entity.getAnnualUsageSourceType()));
    entity.setAnnualUsageSourceBatchNo(trimToNull(entity.getAnnualUsageSourceBatchNo()));
    entity.setAnnualUsageOaNo(trimToNull(entity.getAnnualUsageOaNo()));
    entity.setAnnualUsageOaLineId(trimToNull(entity.getAnnualUsageOaLineId()));
    entity.setMatchRiskReason(trimToNull(entity.getMatchRiskReason()));

    syncNewAndLegacyFields(entity);
    if (entity.getPropertyYear() == null && entity.getId() == null) {
      entity.setPropertyYear(Year.now().getValue());
    }
    if (entity.getPropertyYear() != null) {
      entity.setPeriod(entity.getPropertyYear() + "-01");
    }
    if (!StringUtils.hasText(entity.getParentCode()) && !StringUtils.hasText(entity.getProductCode())) {
      entity.setParentCode(fallbackParentCode(entity));
    }
    if (!StringUtils.hasText(entity.getLevel1Code())) {
      entity.setLevel1Code(firstText(entity.getBusinessDivision(), entity.getLevel1Name(), PLACEHOLDER_LEVEL1_CODE));
    }
    if (!StringUtils.hasText(entity.getLevel1Name())) {
      entity.setLevel1Name(firstText(entity.getBusinessDivision(), PLACEHOLDER_TEXT));
    }
    if (!StringUtils.hasText(entity.getParentName())) {
      entity.setParentName(firstText(entity.getProductName(), PLACEHOLDER_TEXT));
    }
    if (request.isUsageOnly() && !StringUtils.hasText(entity.getProductAttr())) {
      entity.setProductAttr(PLACEHOLDER_TEXT);
    }
    if (entity.getAnnualUsage() != null) {
      entity.setAnnualUsageUpdatedAt(LocalDateTime.now());
    }
    if (entity.getMatchRiskFlag() == null) {
      entity.setMatchRiskFlag(StringUtils.hasText(entity.getProductCode()) ? 0 : 1);
    }
    if (Integer.valueOf(1).equals(entity.getMatchRiskFlag())
        && !StringUtils.hasText(entity.getMatchRiskReason())) {
      entity.setMatchRiskReason(
          StringUtils.hasText(entity.getProductCode()) ? "占位记录，待补齐产品属性" : "缺产品料号，已拒绝同步");
    }
    entity.setCoefficient(deriveCoefficient(entity));
  }

  private void syncNewAndLegacyFields(ProductProperty entity) {
    if (!StringUtils.hasText(entity.getBusinessDivision())) {
      entity.setBusinessDivision(entity.getLevel1Name());
    }
    if (!StringUtils.hasText(entity.getLevel1Name())) {
      entity.setLevel1Name(entity.getBusinessDivision());
    }
    if (!StringUtils.hasText(entity.getProductCode())
        && !isFallbackParentCode(entity.getParentCode())) {
      entity.setProductCode(entity.getParentCode());
    }
    if (!StringUtils.hasText(entity.getParentCode()) && StringUtils.hasText(entity.getProductCode())) {
      entity.setParentCode(entity.getProductCode());
    }
    if (!StringUtils.hasText(entity.getProductName())) {
      entity.setProductName(entity.getParentName());
    }
    if (!StringUtils.hasText(entity.getParentName())) {
      entity.setParentName(entity.getProductName());
    }
    if (!StringUtils.hasText(entity.getProductModel())) {
      entity.setProductModel(entity.getParentModel());
    }
    if (!StringUtils.hasText(entity.getParentModel())) {
      entity.setParentModel(entity.getProductModel());
    }
    if (!StringUtils.hasText(entity.getProductSpec())) {
      entity.setProductSpec(entity.getParentSpec());
    }
    if (!StringUtils.hasText(entity.getParentSpec())) {
      entity.setParentSpec(entity.getProductSpec());
    }
    if (entity.getPropertyYear() == null) {
      entity.setPropertyYear(parseYear(entity.getPeriod()));
    }
  }

  private boolean validate(
      ProductPropertyAnnualSyncRequest request,
      ProductProperty entity,
      String rowLabel,
      ProductPropertyAnnualSyncResult result) {
    if (entity.getId() == null
        && (entity.getPropertyYear() == null
        || entity.getPropertyYear() < 2000
        || entity.getPropertyYear() > 2100)) {
      result.addError(rowLabel + "年度无效");
      return false;
    }
    if (request.isRequireProductCode() && !StringUtils.hasText(entity.getProductCode())) {
      result.addError(rowLabel + "缺产品料号，OA 年用量不能沉淀到产品属性对照表");
      return false;
    }
    if (request.isUsageOnly()) {
      if (entity.getAnnualUsage() == null) {
        result.incrementSkipped();
        result.addWarning(rowLabel + "预计年用量为空，已跳过");
        return false;
      }
      return true;
    }
    if (!StringUtils.hasText(entity.getProductCode())) {
      result.addError(rowLabel + "缺产品料号");
      return false;
    }
    if (!StringUtils.hasText(entity.getProductAttr())) {
      result.addError(rowLabel + "缺产品属性");
      return false;
    }
    return true;
  }

  private ProductProperty findExisting(ProductPropertyAnnualSyncRequest request, ProductProperty entity) {
    if (entity.getId() != null) {
      return productPropertyMapper.selectById(entity.getId());
    }
    if (StringUtils.hasText(entity.getProductCode())) {
      return productPropertyMapper.selectOne(
          Wrappers.lambdaQuery(ProductProperty.class)
              .eq(ProductProperty::getBusinessUnitType, entity.getBusinessUnitType())
              .eq(ProductProperty::getPropertyYear, entity.getPropertyYear())
              .eq(ProductProperty::getProductCode, entity.getProductCode())
              .last("LIMIT 1"));
    }
    return null;
  }

  private void prepareForInsert(ProductPropertyAnnualSyncRequest request, ProductProperty entity) {
    if (request.isUsageOnly() && request.isCreatePlaceholderOnMissing()) {
      entity.setMatchRiskFlag(1);
      if (!StringUtils.hasText(entity.getMatchRiskReason())) {
        entity.setMatchRiskReason("OA 年用量先到，未匹配到技术导入产品属性");
      }
    }
  }

  private void mergeExisting(ProductProperty target, ProductProperty source, boolean usageOnly) {
    if (usageOnly) {
      copyBlankProductFields(target, source);
      if (source.getAnnualUsage() != null) {
        target.setAnnualUsage(source.getAnnualUsage());
        target.setAnnualUsageSourceType(source.getAnnualUsageSourceType());
        target.setAnnualUsageSourceBatchNo(source.getAnnualUsageSourceBatchNo());
        target.setAnnualUsageOaNo(source.getAnnualUsageOaNo());
        target.setAnnualUsageOaLineId(source.getAnnualUsageOaLineId());
        target.setAnnualUsageUpdatedAt(LocalDateTime.now());
      }
      return;
    }
    copyIfPresent(source.getLevel1Code(), target::setLevel1Code);
    copyIfPresent(source.getLevel1Name(), target::setLevel1Name);
    copyIfPresent(source.getParentCode(), target::setParentCode);
    copyIfPresent(source.getParentName(), target::setParentName);
    copyIfPresent(source.getParentSpec(), target::setParentSpec);
    copyIfPresent(source.getParentModel(), target::setParentModel);
    copyIfPresent(source.getPeriod(), target::setPeriod);
    copyIfPresent(source.getProductAttr(), target::setProductAttr);
    if (source.getPropertyYear() != null) {
      target.setPropertyYear(source.getPropertyYear());
    }
    copyIfPresent(source.getBusinessDivision(), target::setBusinessDivision);
    copyIfPresent(source.getProductCode(), target::setProductCode);
    copyIfPresent(source.getProductName(), target::setProductName);
    copyIfPresent(source.getProductModel(), target::setProductModel);
    copyIfPresent(source.getProductSpec(), target::setProductSpec);
    copyIfPresent(source.getRemark(), target::setRemark);
    copyIfPresent(source.getAttrSourceType(), target::setAttrSourceType);
    copyIfPresent(source.getAttrSourceBatchNo(), target::setAttrSourceBatchNo);
    copyIfPresent(source.getAnnualUsageSourceType(), target::setAnnualUsageSourceType);
    copyIfPresent(source.getAnnualUsageSourceBatchNo(), target::setAnnualUsageSourceBatchNo);
    copyIfPresent(source.getAnnualUsageOaNo(), target::setAnnualUsageOaNo);
    copyIfPresent(source.getAnnualUsageOaLineId(), target::setAnnualUsageOaLineId);
    if (source.getAnnualUsage() != null) {
      target.setAnnualUsage(source.getAnnualUsage());
      target.setAnnualUsageUpdatedAt(LocalDateTime.now());
    }
    if (source.getEffectiveFrom() != null) {
      target.setEffectiveFrom(source.getEffectiveFrom());
    }
    if (source.getEffectiveTo() != null) {
      target.setEffectiveTo(source.getEffectiveTo());
    }
    if (source.getMatchRiskFlag() != null) {
      target.setMatchRiskFlag(source.getMatchRiskFlag());
    }
    copyIfPresent(source.getMatchRiskReason(), target::setMatchRiskReason);
  }

  private BigDecimal deriveCoefficient(ProductProperty entity) {
    if (entity == null || !isNonStandardProduct(entity.getProductAttr())) {
      return STANDARD_COEFFICIENT;
    }
    if (entity.getAnnualUsage() != null
        && entity.getAnnualUsage().compareTo(HIGH_ANNUAL_USAGE_THRESHOLD) > 0) {
      return STANDARD_COEFFICIENT;
    }
    return NON_STANDARD_COEFFICIENT;
  }

  private boolean isNonStandardProduct(String productAttr) {
    if (!StringUtils.hasText(productAttr)) {
      return false;
    }
    String value = productAttr.trim();
    return value.contains("非标准") || value.contains("非标");
  }

  private void copyBlankProductFields(ProductProperty target, ProductProperty source) {
    if (isBlankOrPlaceholder(target.getLevel1Code())) {
      target.setLevel1Code(source.getLevel1Code());
    }
    if (isBlankOrPlaceholder(target.getLevel1Name())) {
      target.setLevel1Name(source.getLevel1Name());
    }
    if (isBlankOrPlaceholder(target.getBusinessDivision())) {
      target.setBusinessDivision(source.getBusinessDivision());
    }
    if (isBlankOrPlaceholder(target.getProductName())) {
      target.setProductName(source.getProductName());
      target.setParentName(source.getParentName());
    }
    if (isBlankOrPlaceholder(target.getParentName())) {
      target.setParentName(source.getParentName());
    }
    if (isBlankOrPlaceholder(target.getProductModel())) {
      target.setProductModel(source.getProductModel());
      target.setParentModel(source.getParentModel());
    }
    if (isBlankOrPlaceholder(target.getParentModel())) {
      target.setParentModel(source.getParentModel());
    }
    if (isBlankOrPlaceholder(target.getProductSpec())) {
      target.setProductSpec(source.getProductSpec());
      target.setParentSpec(source.getParentSpec());
    }
    if (isBlankOrPlaceholder(target.getParentSpec())) {
      target.setParentSpec(source.getParentSpec());
    }
  }

  private boolean isBlankOrPlaceholder(String value) {
    String trimmed = value == null ? null : value.trim();
    return !StringUtils.hasText(value)
        || PLACEHOLDER_TEXT.equals(trimmed)
        || PLACEHOLDER_LEVEL1_CODE.equals(trimmed)
        || isUnclosedLeadingParenthetical(trimmed);
  }

  private boolean isUnclosedLeadingParenthetical(String value) {
    if (!StringUtils.hasText(value)) {
      return false;
    }
    return (value.startsWith("（") && !value.contains("）"))
        || (value.startsWith("(") && !value.contains(")"));
  }

  private void copyIfPresent(String value, java.util.function.Consumer<String> setter) {
    if (StringUtils.hasText(value)) {
      setter.accept(value);
    }
  }

  private void countRisk(ProductProperty entity, ProductPropertyAnnualSyncResult result) {
    if (Integer.valueOf(1).equals(entity.getMatchRiskFlag())) {
      result.incrementRisks();
      result.addWarning(
          "产品料号 "
              + firstText(entity.getProductCode(), entity.getParentCode(), "空")
              + " 存在风险："
              + firstText(entity.getMatchRiskReason(), "待补齐"));
    }
  }

  private Integer parseYear(String period) {
    if (!StringUtils.hasText(period) || period.trim().length() < 4) {
      return null;
    }
    try {
      return Integer.valueOf(period.trim().substring(0, 4));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String fallbackParentCode(ProductProperty entity) {
    String raw =
        String.join(
            "|",
            List.of(
                firstText(entity.getBusinessUnitType(), ""),
                String.valueOf(entity.getPropertyYear()),
                firstText(entity.getBusinessDivision(), ""),
                firstText(entity.getProductName(), ""),
                firstText(entity.getProductModel(), ""),
                firstText(entity.getProductSpec(), "")));
    return "NO_CODE_" + Integer.toHexString(Objects.hash(raw));
  }

  private boolean isFallbackParentCode(String parentCode) {
    return StringUtils.hasText(parentCode) && parentCode.trim().startsWith("NO_CODE_");
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }
}
