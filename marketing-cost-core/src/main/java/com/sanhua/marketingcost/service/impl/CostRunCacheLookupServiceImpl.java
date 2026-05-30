package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.entity.OtherExpenseRate;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.entity.ThreeExpenseDimensionMapping;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseDimensionMappingMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import com.sanhua.marketingcost.service.CostRunCacheLookupService;
import java.util.Collections;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * T19：试算路径缓存查询实现。
 *
 * <p>每个方法 {@code @Cacheable} 5 分钟 TTL（CacheConfig 全局），key 用入参；
 * null/空入参单独 key="ALL"，避免 KeyGenerator 默认 0-arg 异常。
 */
@Service
public class CostRunCacheLookupServiceImpl implements CostRunCacheLookupService {

  private final QualityLossRateMapper qualityLossRateMapper;
  private final ManufactureRateMapper manufactureRateMapper;
  private final ThreeExpenseRateMapper threeExpenseRateMapper;
  private final DepartmentFundRateMapper departmentFundRateMapper;
  private final OtherExpenseRateMapper otherExpenseRateMapper;
  private final ProductPropertyMapper productPropertyMapper;
  private final ThreeExpenseDimensionMappingMapper threeExpenseDimensionMappingMapper;

  public CostRunCacheLookupServiceImpl(
      QualityLossRateMapper qualityLossRateMapper,
      ManufactureRateMapper manufactureRateMapper,
      ThreeExpenseRateMapper threeExpenseRateMapper,
      DepartmentFundRateMapper departmentFundRateMapper,
      OtherExpenseRateMapper otherExpenseRateMapper,
      ProductPropertyMapper productPropertyMapper,
      ThreeExpenseDimensionMappingMapper threeExpenseDimensionMappingMapper) {
    this.qualityLossRateMapper = qualityLossRateMapper;
    this.manufactureRateMapper = manufactureRateMapper;
    this.threeExpenseRateMapper = threeExpenseRateMapper;
    this.departmentFundRateMapper = departmentFundRateMapper;
    this.otherExpenseRateMapper = otherExpenseRateMapper;
    this.productPropertyMapper = productPropertyMapper;
    this.threeExpenseDimensionMappingMapper = threeExpenseDimensionMappingMapper;
  }

  @Override
  @Cacheable(value = "qualityLossRates", key = "'trial:' + (#businessUnit ?: 'NULL')")
  public QualityLossRate findQualityLossRate(String businessUnit) {
    if (!StringUtils.hasText(businessUnit)) return null;
    return qualityLossRateMapper.selectOne(
        Wrappers.lambdaQuery(QualityLossRate.class)
            .eq(QualityLossRate::getBusinessUnit, businessUnit)
            .orderByDesc(QualityLossRate::getId)
            .last("LIMIT 1"));
  }

  @Override
  @Cacheable(value = "manufactureRates", key = "'trial:' + (#businessUnit ?: 'NULL')")
  public ManufactureRate findManufactureRate(String businessUnit) {
    if (!StringUtils.hasText(businessUnit)) return null;
    return manufactureRateMapper.selectOne(
        Wrappers.lambdaQuery(ManufactureRate.class)
            .eq(ManufactureRate::getBusinessUnit, businessUnit)
            .orderByDesc(ManufactureRate::getId)
            .last("LIMIT 1"));
  }

  @Override
  @Cacheable(value = "threeExpenseRates", key = "'trial:' + (#businessUnit ?: 'NULL')")
  public ThreeExpenseRate findThreeExpenseRate(String businessUnit) {
    if (!StringUtils.hasText(businessUnit)) return null;
    return threeExpenseRateMapper.selectOne(
        Wrappers.lambdaQuery(ThreeExpenseRate.class)
            .eq(ThreeExpenseRate::getBusinessUnit, businessUnit)
            .orderByDesc(ThreeExpenseRate::getId)
            .last("LIMIT 1"));
  }

  @Override
  @Cacheable(
      value = "threeExpenseRates",
      key =
          "'trial:q10:' + (#businessUnitType ?: 'NULL') + ':' + (#periodMonth ?: 'NULL') + ':' + (#standardCompany ?: 'NULL') + ':' + (#productionDivision ?: 'NULL') + ':' + (#applicantDepartment ?: 'NULL') + ':' + (#applicantOffice ?: 'NULL') + ':' + (#productCategory ?: '') + ':' + (#productLine ?: '')")
  public ThreeExpenseRate findThreeExpenseRate(
      String businessUnitType,
      String periodMonth,
      String standardCompany,
      String productionDivision,
      String applicantDepartment,
      String applicantOffice,
      String productCategory,
      String productLine) {
    if (!StringUtils.hasText(businessUnitType)
        || !StringUtils.hasText(periodMonth)
        || !StringUtils.hasText(standardCompany)
        || !StringUtils.hasText(productionDivision)
        || !StringUtils.hasText(applicantDepartment)
        || !StringUtils.hasText(applicantOffice)) {
      return null;
    }
    return threeExpenseRateMapper.selectOne(
        Wrappers.lambdaQuery(ThreeExpenseRate.class)
            .eq(ThreeExpenseRate::getBusinessUnitType, businessUnitType.trim())
            .eq(ThreeExpenseRate::getPeriodMonth, periodMonth.trim())
            .eq(ThreeExpenseRate::getStandardCompany, standardCompany.trim())
            .eq(ThreeExpenseRate::getProductionDivision, productionDivision.trim())
            .eq(ThreeExpenseRate::getApplicantDepartment, applicantDepartment.trim())
            .eq(ThreeExpenseRate::getApplicantOffice, applicantOffice.trim())
            .eq(ThreeExpenseRate::getProductCategory, trimToEmpty(productCategory))
            .eq(ThreeExpenseRate::getProductLine, trimToEmpty(productLine))
            .orderByDesc(ThreeExpenseRate::getId)
            .last("LIMIT 1"));
  }

  @Override
  @Cacheable(
      value = "threeExpenseRates",
      key =
          "'dimension:' + (#businessUnitType ?: 'NULL') + ':' + (#dimensionType ?: 'NULL') + ':' + (#sourceSystem ?: 'NULL') + ':' + (#sourceValue ?: 'NULL')")
  public ThreeExpenseDimensionMapping findThreeExpenseDimensionMapping(
      String businessUnitType, String dimensionType, String sourceSystem, String sourceValue) {
    if (!StringUtils.hasText(businessUnitType)
        || !StringUtils.hasText(dimensionType)
        || !StringUtils.hasText(sourceValue)) {
      return null;
    }
    String system = StringUtils.hasText(sourceSystem) ? sourceSystem.trim() : "OA";
    return threeExpenseDimensionMappingMapper.selectOne(
        Wrappers.lambdaQuery(ThreeExpenseDimensionMapping.class)
            .eq(ThreeExpenseDimensionMapping::getBusinessUnitType, businessUnitType.trim())
            .eq(ThreeExpenseDimensionMapping::getDimensionType, dimensionType.trim())
            .eq(ThreeExpenseDimensionMapping::getSourceSystem, system)
            .eq(ThreeExpenseDimensionMapping::getSourceValue, sourceValue.trim())
            .eq(ThreeExpenseDimensionMapping::getEnabled, 1)
            .orderByAsc(ThreeExpenseDimensionMapping::getPriority)
            .orderByDesc(ThreeExpenseDimensionMapping::getId)
            .last("LIMIT 1"));
  }

  @Override
  @Cacheable(value = "departmentFundRates", key = "'trial:' + (#businessUnit ?: 'NULL')")
  public DepartmentFundRate findDepartmentFundRate(String businessUnit) {
    if (!StringUtils.hasText(businessUnit)) return null;
    return departmentFundRateMapper.selectOne(
        Wrappers.lambdaQuery(DepartmentFundRate.class)
            .eq(DepartmentFundRate::getBusinessUnit, businessUnit)
            .orderByDesc(DepartmentFundRate::getId)
            .last("LIMIT 1"));
  }

  @Override
  @Cacheable(value = "otherExpenseRates", key = "'trial:' + (#productCode ?: 'NULL')")
  public List<OtherExpenseRate> findOtherExpenseRates(String productCode) {
    if (!StringUtils.hasText(productCode)) return Collections.emptyList();
    return otherExpenseRateMapper.selectList(
        Wrappers.lambdaQuery(OtherExpenseRate.class)
            .eq(OtherExpenseRate::getMaterialCode, productCode.trim())
            .orderByAsc(OtherExpenseRate::getId));
  }

  @Override
  @Cacheable(value = "productProperty", key = "'trial:' + (#parentCode ?: 'NULL')")
  public ProductProperty findProductProperty(String parentCode) {
    if (!StringUtils.hasText(parentCode)) return null;
    return productPropertyMapper.selectOne(
        Wrappers.lambdaQuery(ProductProperty.class)
            .eq(ProductProperty::getParentCode, parentCode.trim())
            .orderByDesc(ProductProperty::getId)
            .last("LIMIT 1"));
  }

  @Override
  @Cacheable(
      value = "productProperty",
      key =
          "'trial:year:' + (#businessUnitType ?: 'NULL') + ':' + (#propertyYear ?: 'NULL') + ':' + (#productCode ?: 'NULL')")
  public ProductProperty findProductProperty(
      String productCode, Integer propertyYear, String businessUnitType) {
    if (!StringUtils.hasText(productCode)) {
      return null;
    }
    String code = productCode.trim();
    String businessUnit = trimToNull(businessUnitType);
    ProductProperty byProductCode =
        productPropertyMapper.selectOne(
            Wrappers.lambdaQuery(ProductProperty.class)
                .eq(StringUtils.hasText(businessUnit), ProductProperty::getBusinessUnitType, businessUnit)
                .eq(propertyYear != null, ProductProperty::getPropertyYear, propertyYear)
                .eq(ProductProperty::getProductCode, code)
                .orderByDesc(ProductProperty::getId)
                .last("LIMIT 1"));
    if (byProductCode != null) {
      return byProductCode;
    }
    return productPropertyMapper.selectOne(
        Wrappers.lambdaQuery(ProductProperty.class)
            .eq(StringUtils.hasText(businessUnit), ProductProperty::getBusinessUnitType, businessUnit)
            .eq(propertyYear != null, ProductProperty::getPropertyYear, propertyYear)
            .eq(ProductProperty::getParentCode, code)
            .orderByDesc(ProductProperty::getId)
            .last("LIMIT 1"));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String trimToEmpty(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? "" : trimmed;
  }
}
