package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.OtherExpenseRateImportRequest;
import com.sanhua.marketingcost.dto.OtherExpenseRateRequest;
import com.sanhua.marketingcost.entity.OtherExpenseRate;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.service.OtherExpenseRateService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OtherExpenseRateServiceImpl implements OtherExpenseRateService {
  private final OtherExpenseRateMapper otherExpenseRateMapper;

  public OtherExpenseRateServiceImpl(OtherExpenseRateMapper otherExpenseRateMapper) {
    this.otherExpenseRateMapper = otherExpenseRateMapper;
  }

  @Override
  @Cacheable(value = "otherExpenseRates", key = "(#materialCode ?: '') + '_' + (#productName ?: '')")
  public List<OtherExpenseRate> list(String materialCode, String productName) {
    var query = Wrappers.lambdaQuery(OtherExpenseRate.class);
    if (StringUtils.hasText(materialCode)) {
      query.like(OtherExpenseRate::getMaterialCode, materialCode.trim());
    }
    if (StringUtils.hasText(productName)) {
      query.like(OtherExpenseRate::getProductName, productName.trim());
    }
    query.orderByDesc(OtherExpenseRate::getId);
    return otherExpenseRateMapper.selectList(query);
  }

  @Override
  @CacheEvict(value = "otherExpenseRates", allEntries = true)
  public OtherExpenseRate create(OtherExpenseRateRequest request) {
    if (request == null) {
      return null;
    }
    OtherExpenseRate entity = new OtherExpenseRate();
    merge(entity, request);
    fillDefaults(entity);
    if (!hasRequired(entity)) {
      return null;
    }
    otherExpenseRateMapper.insert(entity);
    return entity;
  }

  @Override
  @CacheEvict(value = "otherExpenseRates", allEntries = true)
  public OtherExpenseRate update(Long id, OtherExpenseRateRequest request) {
    if (id == null) {
      return null;
    }
    OtherExpenseRate existing = otherExpenseRateMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    otherExpenseRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "otherExpenseRates", allEntries = true)
  public boolean delete(Long id) {
    return id != null && otherExpenseRateMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "otherExpenseRates", allEntries = true)
  public List<OtherExpenseRate> importItems(OtherExpenseRateImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<OtherExpenseRate> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null) {
        continue;
      }
      OtherExpenseRate entity = new OtherExpenseRate();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequired(entity)) {
        continue;
      }
      OtherExpenseRate existing = findExisting(entity);
      if (existing == null) {
        otherExpenseRateMapper.insert(entity);
        imported.add(entity);
      } else {
        merge(existing, entity);
        otherExpenseRateMapper.updateById(existing);
        imported.add(existing);
      }
    }
    return imported;
  }

  private void fillFromRow(OtherExpenseRate entity, OtherExpenseRateImportRequest.OtherExpenseRateRow row) {
    entity.setMaterialCode(row.getMaterialCode());
    entity.setProductName(row.getProductName());
    entity.setSpec(row.getSpec());
    entity.setModel(row.getModel());
    entity.setCustomer(row.getCustomer());
    entity.setExpenseType(row.getExpenseType());
    entity.setExpenseAmount(row.getExpenseAmount());
  }

  private void merge(OtherExpenseRate target, OtherExpenseRate source) {
    if (source.getMaterialCode() != null) {
      target.setMaterialCode(source.getMaterialCode());
    }
    if (source.getProductName() != null) {
      target.setProductName(source.getProductName());
    }
    if (source.getSpec() != null) {
      target.setSpec(source.getSpec());
    }
    if (source.getModel() != null) {
      target.setModel(source.getModel());
    }
    if (source.getCustomer() != null) {
      target.setCustomer(source.getCustomer());
    }
    if (source.getExpenseType() != null) {
      target.setExpenseType(source.getExpenseType());
    }
    if (source.getExpenseAmount() != null) {
      target.setExpenseAmount(source.getExpenseAmount());
    }
  }

  private void merge(OtherExpenseRate entity, OtherExpenseRateRequest request) {
    if (request == null) {
      return;
    }
    if (request.getMaterialCode() != null) {
      entity.setMaterialCode(request.getMaterialCode());
    }
    if (request.getProductName() != null) {
      entity.setProductName(request.getProductName());
    }
    if (request.getSpec() != null) {
      entity.setSpec(request.getSpec());
    }
    if (request.getModel() != null) {
      entity.setModel(request.getModel());
    }
    if (request.getCustomer() != null) {
      entity.setCustomer(request.getCustomer());
    }
    if (request.getExpenseType() != null) {
      entity.setExpenseType(request.getExpenseType());
    }
    if (request.getExpenseAmount() != null) {
      entity.setExpenseAmount(request.getExpenseAmount());
    }
  }

  private void fillDefaults(OtherExpenseRate entity) {
    entity.setMaterialCode(trimToNull(entity.getMaterialCode()));
    entity.setProductName(trimToNull(entity.getProductName()));
    entity.setSpec(trimToNull(entity.getSpec()));
    entity.setModel(trimToNull(entity.getModel()));
    entity.setCustomer(trimToNull(entity.getCustomer()));
    entity.setExpenseType(trimToNull(entity.getExpenseType()));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private boolean hasRequired(OtherExpenseRate entity) {
    return StringUtils.hasText(entity.getMaterialCode())
        && StringUtils.hasText(entity.getExpenseType())
        && entity.getExpenseAmount() != null;
  }

  private OtherExpenseRate findExisting(OtherExpenseRate entity) {
    var query = Wrappers.lambdaQuery(OtherExpenseRate.class)
        .eq(OtherExpenseRate::getMaterialCode, entity.getMaterialCode());
    if (StringUtils.hasText(entity.getCustomer())) {
      query.eq(OtherExpenseRate::getCustomer, entity.getCustomer());
    } else {
      query.isNull(OtherExpenseRate::getCustomer);
    }
    if (StringUtils.hasText(entity.getExpenseType())) {
      query.eq(OtherExpenseRate::getExpenseType, entity.getExpenseType());
    } else {
      query.isNull(OtherExpenseRate::getExpenseType);
    }
    return otherExpenseRateMapper.selectOne(query.last("LIMIT 1"));
  }
}
