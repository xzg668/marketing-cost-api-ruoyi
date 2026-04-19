package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.DepartmentFundRateImportRequest;
import com.sanhua.marketingcost.dto.DepartmentFundRateRequest;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.service.DepartmentFundRateService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentFundRateServiceImpl implements DepartmentFundRateService {
  private final DepartmentFundRateMapper departmentFundRateMapper;

  public DepartmentFundRateServiceImpl(DepartmentFundRateMapper departmentFundRateMapper) {
    this.departmentFundRateMapper = departmentFundRateMapper;
  }

  @Override
  @Cacheable(value = "departmentFundRates", key = "#businessUnit ?: 'ALL'")
  public List<DepartmentFundRate> list(String businessUnit) {
    var query = Wrappers.lambdaQuery(DepartmentFundRate.class);
    if (StringUtils.hasText(businessUnit)) {
      query.like(DepartmentFundRate::getBusinessUnit, businessUnit.trim());
    }
    query.orderByDesc(DepartmentFundRate::getId);
    return departmentFundRateMapper.selectList(query);
  }

  @Override
  @CacheEvict(value = "departmentFundRates", allEntries = true)
  public DepartmentFundRate create(DepartmentFundRateRequest request) {
    if (request == null) {
      return null;
    }
    DepartmentFundRate entity = new DepartmentFundRate();
    merge(entity, request);
    fillDefaults(entity);
    if (!hasRequired(entity)) {
      return null;
    }
    departmentFundRateMapper.insert(entity);
    return entity;
  }

  @Override
  @CacheEvict(value = "departmentFundRates", allEntries = true)
  public DepartmentFundRate update(Long id, DepartmentFundRateRequest request) {
    if (id == null) {
      return null;
    }
    DepartmentFundRate existing = departmentFundRateMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    departmentFundRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "departmentFundRates", allEntries = true)
  public boolean delete(Long id) {
    return id != null && departmentFundRateMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "departmentFundRates", allEntries = true)
  public List<DepartmentFundRate> importItems(DepartmentFundRateImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<DepartmentFundRate> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null) {
        continue;
      }
      DepartmentFundRate entity = new DepartmentFundRate();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequired(entity)) {
        continue;
      }
      DepartmentFundRate existing = findExisting(entity);
      if (existing == null) {
        departmentFundRateMapper.insert(entity);
        imported.add(entity);
      } else {
        merge(existing, entity);
        departmentFundRateMapper.updateById(existing);
        imported.add(existing);
      }
    }
    return imported;
  }

  private void fillFromRow(DepartmentFundRate entity, DepartmentFundRateImportRequest.DepartmentFundRateRow row) {
    entity.setBusinessUnit(row.getBusinessUnit());
    entity.setOverhaulRate(row.getOverhaulRate());
    entity.setToolingRepairRate(row.getToolingRepairRate());
    entity.setWaterPowerRate(row.getWaterPowerRate());
    entity.setOtherRate(row.getOtherRate());
    entity.setUpliftRate(row.getUpliftRate());
    entity.setManhourRate(row.getManhourRate());
  }

  private void merge(DepartmentFundRate entity, DepartmentFundRateRequest request) {
    if (request == null) {
      return;
    }
    if (request.getBusinessUnit() != null) {
      entity.setBusinessUnit(request.getBusinessUnit());
    }
    if (request.getOverhaulRate() != null) {
      entity.setOverhaulRate(request.getOverhaulRate());
    }
    if (request.getToolingRepairRate() != null) {
      entity.setToolingRepairRate(request.getToolingRepairRate());
    }
    if (request.getWaterPowerRate() != null) {
      entity.setWaterPowerRate(request.getWaterPowerRate());
    }
    if (request.getOtherRate() != null) {
      entity.setOtherRate(request.getOtherRate());
    }
    if (request.getUpliftRate() != null) {
      entity.setUpliftRate(request.getUpliftRate());
    }
    if (request.getManhourRate() != null) {
      entity.setManhourRate(request.getManhourRate());
    }
  }

  private void merge(DepartmentFundRate target, DepartmentFundRate source) {
    if (source.getBusinessUnit() != null) {
      target.setBusinessUnit(source.getBusinessUnit());
    }
    if (source.getOverhaulRate() != null) {
      target.setOverhaulRate(source.getOverhaulRate());
    }
    if (source.getToolingRepairRate() != null) {
      target.setToolingRepairRate(source.getToolingRepairRate());
    }
    if (source.getWaterPowerRate() != null) {
      target.setWaterPowerRate(source.getWaterPowerRate());
    }
    if (source.getOtherRate() != null) {
      target.setOtherRate(source.getOtherRate());
    }
    if (source.getUpliftRate() != null) {
      target.setUpliftRate(source.getUpliftRate());
    }
    if (source.getManhourRate() != null) {
      target.setManhourRate(source.getManhourRate());
    }
  }

  private void fillDefaults(DepartmentFundRate entity) {
    entity.setBusinessUnit(trimToNull(entity.getBusinessUnit()));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private boolean hasRequired(DepartmentFundRate entity) {
    return StringUtils.hasText(entity.getBusinessUnit())
        && entity.getOverhaulRate() != null
        && entity.getToolingRepairRate() != null
        && entity.getWaterPowerRate() != null
        && entity.getOtherRate() != null
        && entity.getUpliftRate() != null
        && entity.getManhourRate() != null;
  }

  private DepartmentFundRate findExisting(DepartmentFundRate entity) {
    return departmentFundRateMapper.selectOne(
        Wrappers.lambdaQuery(DepartmentFundRate.class)
            .eq(DepartmentFundRate::getBusinessUnit, entity.getBusinessUnit())
            .last("LIMIT 1"));
  }
}
