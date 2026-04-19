package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ThreeExpenseRateImportRequest;
import com.sanhua.marketingcost.dto.ThreeExpenseRateRequest;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import com.sanhua.marketingcost.service.ThreeExpenseRateService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThreeExpenseRateServiceImpl implements ThreeExpenseRateService {
  private final ThreeExpenseRateMapper threeExpenseRateMapper;

  public ThreeExpenseRateServiceImpl(ThreeExpenseRateMapper threeExpenseRateMapper) {
    this.threeExpenseRateMapper = threeExpenseRateMapper;
  }

  @Override
  @Cacheable(value = "threeExpenseRates", key = "#department ?: 'ALL'")
  public List<ThreeExpenseRate> list(String department) {
    var query = Wrappers.lambdaQuery(ThreeExpenseRate.class);
    if (StringUtils.hasText(department)) {
      query.like(ThreeExpenseRate::getDepartment, department.trim());
    }
    query.orderByDesc(ThreeExpenseRate::getPeriod).orderByDesc(ThreeExpenseRate::getId);
    return threeExpenseRateMapper.selectList(query);
  }

  @Override
  @CacheEvict(value = "threeExpenseRates", allEntries = true)
  public ThreeExpenseRate create(ThreeExpenseRateRequest request) {
    if (request == null) {
      return null;
    }
    ThreeExpenseRate entity = new ThreeExpenseRate();
    merge(entity, request);
    fillDefaults(entity);
    if (!hasRequired(entity)) {
      return null;
    }
    threeExpenseRateMapper.insert(entity);
    return entity;
  }

  @Override
  @CacheEvict(value = "threeExpenseRates", allEntries = true)
  public ThreeExpenseRate update(Long id, ThreeExpenseRateRequest request) {
    if (id == null) {
      return null;
    }
    ThreeExpenseRate existing = threeExpenseRateMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    threeExpenseRateMapper.updateById(existing);
    return existing;
  }

  @Override
  @CacheEvict(value = "threeExpenseRates", allEntries = true)
  public boolean delete(Long id) {
    return id != null && threeExpenseRateMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "threeExpenseRates", allEntries = true)
  public List<ThreeExpenseRate> importItems(ThreeExpenseRateImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<ThreeExpenseRate> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null) {
        continue;
      }
      ThreeExpenseRate entity = new ThreeExpenseRate();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequired(entity)) {
        continue;
      }
      ThreeExpenseRate existing = findExisting(entity);
      if (existing == null) {
        threeExpenseRateMapper.insert(entity);
        imported.add(entity);
      } else {
        merge(existing, entity);
        threeExpenseRateMapper.updateById(existing);
        imported.add(existing);
      }
    }
    return imported;
  }

  private void fillFromRow(ThreeExpenseRate entity, ThreeExpenseRateImportRequest.ThreeExpenseRateRow row) {
    entity.setCompany(row.getCompany());
    entity.setBusinessUnit(row.getBusinessUnit());
    entity.setDepartment(row.getDepartment());
    entity.setManagementExpenseRate(row.getManagementExpenseRate());
    entity.setFinanceExpenseRate(row.getFinanceExpenseRate());
    entity.setSalesExpenseRate(row.getSalesExpenseRate());
    entity.setThreeExpenseRate2025(row.getThreeExpenseRate2025());
    entity.setThreeExpenseRate2026(row.getThreeExpenseRate2026());
    entity.setOverseasSales(row.getOverseasSales());
    entity.setPeriod(row.getPeriod());
  }

  private void merge(ThreeExpenseRate entity, ThreeExpenseRateRequest request) {
    if (request == null) {
      return;
    }
    if (request.getCompany() != null) {
      entity.setCompany(request.getCompany());
    }
    if (request.getBusinessUnit() != null) {
      entity.setBusinessUnit(request.getBusinessUnit());
    }
    if (request.getDepartment() != null) {
      entity.setDepartment(request.getDepartment());
    }
    if (request.getManagementExpenseRate() != null) {
      entity.setManagementExpenseRate(request.getManagementExpenseRate());
    }
    if (request.getFinanceExpenseRate() != null) {
      entity.setFinanceExpenseRate(request.getFinanceExpenseRate());
    }
    if (request.getSalesExpenseRate() != null) {
      entity.setSalesExpenseRate(request.getSalesExpenseRate());
    }
    if (request.getThreeExpenseRate2025() != null) {
      entity.setThreeExpenseRate2025(request.getThreeExpenseRate2025());
    }
    if (request.getThreeExpenseRate2026() != null) {
      entity.setThreeExpenseRate2026(request.getThreeExpenseRate2026());
    }
    if (request.getOverseasSales() != null) {
      entity.setOverseasSales(request.getOverseasSales());
    }
    if (request.getPeriod() != null) {
      entity.setPeriod(request.getPeriod());
    }
  }

  private void merge(ThreeExpenseRate target, ThreeExpenseRate source) {
    if (source.getCompany() != null) {
      target.setCompany(source.getCompany());
    }
    if (source.getBusinessUnit() != null) {
      target.setBusinessUnit(source.getBusinessUnit());
    }
    if (source.getDepartment() != null) {
      target.setDepartment(source.getDepartment());
    }
    if (source.getManagementExpenseRate() != null) {
      target.setManagementExpenseRate(source.getManagementExpenseRate());
    }
    if (source.getFinanceExpenseRate() != null) {
      target.setFinanceExpenseRate(source.getFinanceExpenseRate());
    }
    if (source.getSalesExpenseRate() != null) {
      target.setSalesExpenseRate(source.getSalesExpenseRate());
    }
    if (source.getThreeExpenseRate2025() != null) {
      target.setThreeExpenseRate2025(source.getThreeExpenseRate2025());
    }
    if (source.getThreeExpenseRate2026() != null) {
      target.setThreeExpenseRate2026(source.getThreeExpenseRate2026());
    }
    if (source.getOverseasSales() != null) {
      target.setOverseasSales(source.getOverseasSales());
    }
    if (source.getPeriod() != null) {
      target.setPeriod(source.getPeriod());
    }
  }

  private void fillDefaults(ThreeExpenseRate entity) {
    entity.setCompany(trimToNull(entity.getCompany()));
    entity.setBusinessUnit(trimToNull(entity.getBusinessUnit()));
    entity.setDepartment(trimToNull(entity.getDepartment()));
    entity.setOverseasSales(trimToNull(entity.getOverseasSales()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private boolean hasRequired(ThreeExpenseRate entity) {
    return StringUtils.hasText(entity.getCompany())
        && StringUtils.hasText(entity.getBusinessUnit())
        && StringUtils.hasText(entity.getDepartment())
        && entity.getManagementExpenseRate() != null
        && entity.getFinanceExpenseRate() != null
        && entity.getSalesExpenseRate() != null
        && entity.getThreeExpenseRate2025() != null
        && entity.getThreeExpenseRate2026() != null
        && StringUtils.hasText(entity.getPeriod());
  }

  private ThreeExpenseRate findExisting(ThreeExpenseRate entity) {
    var query = Wrappers.lambdaQuery(ThreeExpenseRate.class)
        .eq(ThreeExpenseRate::getCompany, entity.getCompany())
        .eq(ThreeExpenseRate::getBusinessUnit, entity.getBusinessUnit())
        .eq(ThreeExpenseRate::getDepartment, entity.getDepartment());
    if (StringUtils.hasText(entity.getPeriod())) {
      query.eq(ThreeExpenseRate::getPeriod, entity.getPeriod());
    } else {
      query.isNull(ThreeExpenseRate::getPeriod);
    }
    return threeExpenseRateMapper.selectOne(query.last("LIMIT 1"));
  }
}
