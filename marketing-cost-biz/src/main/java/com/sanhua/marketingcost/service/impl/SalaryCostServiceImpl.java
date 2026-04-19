package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.SalaryCostImportRequest;
import com.sanhua.marketingcost.dto.SalaryCostRequest;
import com.sanhua.marketingcost.entity.SalaryCost;
import com.sanhua.marketingcost.mapper.SalaryCostMapper;
import com.sanhua.marketingcost.service.SalaryCostService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalaryCostServiceImpl implements SalaryCostService {
  private final SalaryCostMapper salaryCostMapper;

  public SalaryCostServiceImpl(SalaryCostMapper salaryCostMapper) {
    this.salaryCostMapper = salaryCostMapper;
  }

  @Override
  public List<SalaryCost> list(String materialCode, String businessUnit) {
    var query = Wrappers.lambdaQuery(SalaryCost.class);
    if (StringUtils.hasText(materialCode)) {
      query.like(SalaryCost::getMaterialCode, materialCode.trim());
    }
    if (StringUtils.hasText(businessUnit)) {
      query.like(SalaryCost::getBusinessUnit, businessUnit.trim());
    }
    query.orderByDesc(SalaryCost::getId);
    return salaryCostMapper.selectList(query);
  }

  @Override
  public SalaryCost create(SalaryCostRequest request) {
    if (request == null) {
      return null;
    }
    SalaryCost entity = new SalaryCost();
    merge(entity, request);
    fillDefaults(entity);
    if (!hasRequired(entity)) {
      return null;
    }
    salaryCostMapper.insert(entity);
    return entity;
  }

  @Override
  public SalaryCost update(Long id, SalaryCostRequest request) {
    if (id == null) {
      return null;
    }
    SalaryCost existing = salaryCostMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    salaryCostMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && salaryCostMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<SalaryCost> importItems(SalaryCostImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<SalaryCost> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null) {
        continue;
      }
      SalaryCost entity = new SalaryCost();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequired(entity)) {
        continue;
      }
      SalaryCost existing = findExisting(entity);
      if (existing == null) {
        salaryCostMapper.insert(entity);
        imported.add(entity);
      } else {
        merge(existing, entity);
        salaryCostMapper.updateById(existing);
        imported.add(existing);
      }
    }
    return imported;
  }

  private void fillFromRow(SalaryCost entity, SalaryCostImportRequest.SalaryCostRow row) {
    entity.setMaterialCode(row.getMaterialCode());
    entity.setProductName(row.getProductName());
    entity.setSpec(row.getSpec());
    entity.setModel(row.getModel());
    entity.setRefMaterialCode(row.getRefMaterialCode());
    entity.setDirectLaborCost(row.getDirectLaborCost());
    entity.setIndirectLaborCost(row.getIndirectLaborCost());
    entity.setSource(row.getSource());
    entity.setBusinessUnit(row.getBusinessUnit());
  }

  private void merge(SalaryCost entity, SalaryCostRequest request) {
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
    if (request.getRefMaterialCode() != null) {
      entity.setRefMaterialCode(request.getRefMaterialCode());
    }
    if (request.getDirectLaborCost() != null) {
      entity.setDirectLaborCost(request.getDirectLaborCost());
    }
    if (request.getIndirectLaborCost() != null) {
      entity.setIndirectLaborCost(request.getIndirectLaborCost());
    }
    if (request.getSource() != null) {
      entity.setSource(request.getSource());
    }
    if (request.getBusinessUnit() != null) {
      entity.setBusinessUnit(request.getBusinessUnit());
    }
  }

  private void merge(SalaryCost target, SalaryCost source) {
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
    if (source.getRefMaterialCode() != null) {
      target.setRefMaterialCode(source.getRefMaterialCode());
    }
    if (source.getDirectLaborCost() != null) {
      target.setDirectLaborCost(source.getDirectLaborCost());
    }
    if (source.getIndirectLaborCost() != null) {
      target.setIndirectLaborCost(source.getIndirectLaborCost());
    }
    if (source.getSource() != null) {
      target.setSource(source.getSource());
    }
    if (source.getBusinessUnit() != null) {
      target.setBusinessUnit(source.getBusinessUnit());
    }
  }

  private void fillDefaults(SalaryCost entity) {
    entity.setMaterialCode(trimToNull(entity.getMaterialCode()));
    entity.setProductName(trimToNull(entity.getProductName()));
    entity.setSpec(trimToNull(entity.getSpec()));
    entity.setModel(trimToNull(entity.getModel()));
    entity.setRefMaterialCode(trimToNull(entity.getRefMaterialCode()));
    entity.setSource(trimToNull(entity.getSource()));
    entity.setBusinessUnit(trimToNull(entity.getBusinessUnit()));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private boolean hasRequired(SalaryCost entity) {
    return StringUtils.hasText(entity.getMaterialCode())
        && entity.getDirectLaborCost() != null
        && entity.getIndirectLaborCost() != null;
  }

  private SalaryCost findExisting(SalaryCost entity) {
    var query = Wrappers.lambdaQuery(SalaryCost.class)
        .eq(SalaryCost::getMaterialCode, entity.getMaterialCode());
    if (StringUtils.hasText(entity.getRefMaterialCode())) {
      query.eq(SalaryCost::getRefMaterialCode, entity.getRefMaterialCode());
    } else {
      query.isNull(SalaryCost::getRefMaterialCode);
    }
    if (StringUtils.hasText(entity.getBusinessUnit())) {
      query.eq(SalaryCost::getBusinessUnit, entity.getBusinessUnit());
    } else {
      query.isNull(SalaryCost::getBusinessUnit);
    }
    if (StringUtils.hasText(entity.getSource())) {
      query.eq(SalaryCost::getSource, entity.getSource());
    } else {
      query.isNull(SalaryCost::getSource);
    }
    return salaryCostMapper.selectOne(query.last("LIMIT 1"));
  }
}
