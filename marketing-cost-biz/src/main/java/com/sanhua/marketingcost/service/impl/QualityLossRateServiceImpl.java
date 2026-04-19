package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.QualityLossRateImportRequest;
import com.sanhua.marketingcost.dto.QualityLossRateRequest;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.service.QualityLossRateService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QualityLossRateServiceImpl implements QualityLossRateService {
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
    query.orderByDesc(QualityLossRate::getPeriod).orderByDesc(QualityLossRate::getId);
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
    fillDefaults(entity);
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
    fillDefaults(existing);
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
  public List<QualityLossRate> importItems(QualityLossRateImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<QualityLossRate> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null) {
        continue;
      }
      QualityLossRate entity = new QualityLossRate();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequired(entity)) {
        continue;
      }
      QualityLossRate existing = findExisting(entity);
      if (existing == null) {
        qualityLossRateMapper.insert(entity);
        imported.add(entity);
      } else {
        merge(existing, entity);
        qualityLossRateMapper.updateById(existing);
        imported.add(existing);
      }
    }
    return imported;
  }

  private void fillFromRow(QualityLossRate entity, QualityLossRateImportRequest.QualityLossRateRow row) {
    entity.setCompany(row.getCompany());
    entity.setBusinessUnit(row.getBusinessUnit());
    entity.setProductCategory(row.getProductCategory());
    entity.setProductSubcategory(row.getProductSubcategory());
    entity.setLossRate(row.getLossRate());
    entity.setCustomer(row.getCustomer());
    entity.setPeriod(row.getPeriod());
    entity.setSourceBasis(row.getSourceBasis());
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
    if (request.getProductCategory() != null) {
      entity.setProductCategory(request.getProductCategory());
    }
    if (request.getProductSubcategory() != null) {
      entity.setProductSubcategory(request.getProductSubcategory());
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
    if (request.getSourceBasis() != null) {
      entity.setSourceBasis(request.getSourceBasis());
    }
  }

  private void merge(QualityLossRate target, QualityLossRate source) {
    if (source.getCompany() != null) {
      target.setCompany(source.getCompany());
    }
    if (source.getBusinessUnit() != null) {
      target.setBusinessUnit(source.getBusinessUnit());
    }
    if (source.getProductCategory() != null) {
      target.setProductCategory(source.getProductCategory());
    }
    if (source.getProductSubcategory() != null) {
      target.setProductSubcategory(source.getProductSubcategory());
    }
    if (source.getLossRate() != null) {
      target.setLossRate(source.getLossRate());
    }
    if (source.getCustomer() != null) {
      target.setCustomer(source.getCustomer());
    }
    if (source.getPeriod() != null) {
      target.setPeriod(source.getPeriod());
    }
    if (source.getSourceBasis() != null) {
      target.setSourceBasis(source.getSourceBasis());
    }
  }

  private void fillDefaults(QualityLossRate entity) {
    entity.setCompany(trimToNull(entity.getCompany()));
    entity.setBusinessUnit(trimToNull(entity.getBusinessUnit()));
    entity.setProductCategory(trimToNull(entity.getProductCategory()));
    entity.setProductSubcategory(trimToNull(entity.getProductSubcategory()));
    entity.setCustomer(trimToNull(entity.getCustomer()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
    entity.setSourceBasis(trimToNull(entity.getSourceBasis()));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private boolean hasRequired(QualityLossRate entity) {
    return StringUtils.hasText(entity.getCompany())
        && StringUtils.hasText(entity.getBusinessUnit())
        && StringUtils.hasText(entity.getProductCategory())
        && StringUtils.hasText(entity.getProductSubcategory())
        && entity.getLossRate() != null
        && StringUtils.hasText(entity.getPeriod());
  }

  private QualityLossRate findExisting(QualityLossRate entity) {
    var query = Wrappers.lambdaQuery(QualityLossRate.class)
        .eq(QualityLossRate::getCompany, entity.getCompany())
        .eq(QualityLossRate::getBusinessUnit, entity.getBusinessUnit())
        .eq(QualityLossRate::getProductCategory, entity.getProductCategory())
        .eq(QualityLossRate::getProductSubcategory, entity.getProductSubcategory());
    if (StringUtils.hasText(entity.getCustomer())) {
      query.eq(QualityLossRate::getCustomer, entity.getCustomer());
    } else {
      query.isNull(QualityLossRate::getCustomer);
    }
    if (StringUtils.hasText(entity.getPeriod())) {
      query.eq(QualityLossRate::getPeriod, entity.getPeriod());
    } else {
      query.isNull(QualityLossRate::getPeriod);
    }
    return qualityLossRateMapper.selectOne(query.last("LIMIT 1"));
  }
}
