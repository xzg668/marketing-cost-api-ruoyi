package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.entity.OtherExpenseRate;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
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

  public CostRunCacheLookupServiceImpl(
      QualityLossRateMapper qualityLossRateMapper,
      ManufactureRateMapper manufactureRateMapper,
      ThreeExpenseRateMapper threeExpenseRateMapper,
      DepartmentFundRateMapper departmentFundRateMapper,
      OtherExpenseRateMapper otherExpenseRateMapper,
      ProductPropertyMapper productPropertyMapper) {
    this.qualityLossRateMapper = qualityLossRateMapper;
    this.manufactureRateMapper = manufactureRateMapper;
    this.threeExpenseRateMapper = threeExpenseRateMapper;
    this.departmentFundRateMapper = departmentFundRateMapper;
    this.otherExpenseRateMapper = otherExpenseRateMapper;
    this.productPropertyMapper = productPropertyMapper;
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
}
