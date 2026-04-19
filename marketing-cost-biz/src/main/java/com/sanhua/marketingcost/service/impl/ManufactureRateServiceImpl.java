package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ManufactureRateImportRequest;
import com.sanhua.marketingcost.dto.ManufactureRateRequest;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.service.ManufactureRateService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManufactureRateServiceImpl implements ManufactureRateService {
  private final ManufactureRateMapper manufactureRateMapper;

  public ManufactureRateServiceImpl(ManufactureRateMapper manufactureRateMapper) {
    this.manufactureRateMapper = manufactureRateMapper;
  }

  @Override
  @Cacheable(value = "manufactureRates", key = "#businessUnit ?: 'ALL'")
  public List<ManufactureRate> list(String businessUnit) {
    var query = Wrappers.lambdaQuery(ManufactureRate.class);
    if (StringUtils.hasText(businessUnit)) {
      query.like(ManufactureRate::getBusinessUnit, businessUnit.trim());
    }
    query.orderByDesc(ManufactureRate::getPeriod).orderByDesc(ManufactureRate::getId);
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
    fillDefaults(entity);
    if (!hasRequired(entity)) {
      return null;
    }
    manufactureRateMapper.insert(entity);
    return entity;
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
    fillDefaults(existing);
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
  public List<ManufactureRate> importItems(ManufactureRateImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<ManufactureRate> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null) {
        continue;
      }
      ManufactureRate entity = new ManufactureRate();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequired(entity)) {
        continue;
      }
      ManufactureRate existing = findExisting(entity);
      if (existing == null) {
        manufactureRateMapper.insert(entity);
        imported.add(entity);
      } else {
        merge(existing, entity);
        manufactureRateMapper.updateById(existing);
        imported.add(existing);
      }
    }
    return imported;
  }

  private void fillFromRow(ManufactureRate entity, ManufactureRateImportRequest.ManufactureRateRow row) {
    entity.setCompany(row.getCompany());
    entity.setBusinessUnit(row.getBusinessUnit());
    entity.setProductCategory(row.getProductCategory());
    entity.setProductSubcategory(row.getProductSubcategory());
    entity.setProductSpec(row.getProductSpec());
    entity.setProductModel(row.getProductModel());
    entity.setFeeRate(row.getFeeRate());
    entity.setPeriod(row.getPeriod());
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
    if (request.getProductCategory() != null) {
      entity.setProductCategory(request.getProductCategory());
    }
    if (request.getProductSubcategory() != null) {
      entity.setProductSubcategory(request.getProductSubcategory());
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
  }

  private void merge(ManufactureRate target, ManufactureRate source) {
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
    if (source.getProductSpec() != null) {
      target.setProductSpec(source.getProductSpec());
    }
    if (source.getProductModel() != null) {
      target.setProductModel(source.getProductModel());
    }
    if (source.getFeeRate() != null) {
      target.setFeeRate(source.getFeeRate());
    }
    if (source.getPeriod() != null) {
      target.setPeriod(source.getPeriod());
    }
  }

  private void fillDefaults(ManufactureRate entity) {
    entity.setCompany(trimToNull(entity.getCompany()));
    entity.setBusinessUnit(trimToNull(entity.getBusinessUnit()));
    entity.setProductCategory(trimToNull(entity.getProductCategory()));
    entity.setProductSubcategory(trimToNull(entity.getProductSubcategory()));
    entity.setProductSpec(trimToNull(entity.getProductSpec()));
    entity.setProductModel(trimToNull(entity.getProductModel()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private boolean hasRequired(ManufactureRate entity) {
    return StringUtils.hasText(entity.getCompany())
        && StringUtils.hasText(entity.getBusinessUnit())
        && StringUtils.hasText(entity.getProductCategory())
        && StringUtils.hasText(entity.getProductSubcategory())
        && entity.getFeeRate() != null
        && StringUtils.hasText(entity.getPeriod());
  }

  private ManufactureRate findExisting(ManufactureRate entity) {
    var query = Wrappers.lambdaQuery(ManufactureRate.class)
        .eq(ManufactureRate::getCompany, entity.getCompany())
        .eq(ManufactureRate::getBusinessUnit, entity.getBusinessUnit())
        .eq(ManufactureRate::getProductCategory, entity.getProductCategory())
        .eq(ManufactureRate::getProductSubcategory, entity.getProductSubcategory());
    if (StringUtils.hasText(entity.getPeriod())) {
      query.eq(ManufactureRate::getPeriod, entity.getPeriod());
    } else {
      query.isNull(ManufactureRate::getPeriod);
    }
    return manufactureRateMapper.selectOne(query.last("LIMIT 1"));
  }
}
