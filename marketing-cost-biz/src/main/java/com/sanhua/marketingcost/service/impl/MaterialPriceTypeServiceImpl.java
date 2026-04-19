package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MaterialPriceTypeImportRequest;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialPriceTypeServiceImpl implements MaterialPriceTypeService {
  private static final String DEFAULT_SOURCE = "import";

  private final MaterialPriceTypeMapper materialPriceTypeMapper;

  public MaterialPriceTypeServiceImpl(MaterialPriceTypeMapper materialPriceTypeMapper) {
    this.materialPriceTypeMapper = materialPriceTypeMapper;
  }

  @Override
  public Page<MaterialPriceType> page(
      String billNo, String materialCode, String priceType, String period, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(MaterialPriceType.class);
    if (StringUtils.hasText(billNo)) {
      query.like(MaterialPriceType::getBillNo, billNo.trim());
    }
    if (StringUtils.hasText(materialCode)) {
      query.like(MaterialPriceType::getMaterialCode, materialCode.trim());
    }
    if (StringUtils.hasText(priceType)) {
      query.eq(MaterialPriceType::getPriceType, priceType.trim());
    }
    if (StringUtils.hasText(period)) {
      query.eq(MaterialPriceType::getPeriod, period.trim());
    }
    query.orderByDesc(MaterialPriceType::getPeriod).orderByDesc(MaterialPriceType::getId);
    Page<MaterialPriceType> pager = new Page<>(page, pageSize);
    return materialPriceTypeMapper.selectPage(pager, query);
  }

  @Override
  public MaterialPriceType create(MaterialPriceTypeRequest request) {
    if (request == null) {
      return null;
    }
    MaterialPriceType entity = new MaterialPriceType();
    merge(entity, request);
    fillDefaults(entity);
    if (!StringUtils.hasText(entity.getBillNo())
        || !StringUtils.hasText(entity.getMaterialCode())
        || !StringUtils.hasText(entity.getPriceType())
        || !StringUtils.hasText(entity.getPeriod())) {
      return null;
    }
    materialPriceTypeMapper.insert(entity);
    return entity;
  }

  @Override
  public MaterialPriceType update(Long id, MaterialPriceTypeRequest request) {
    if (id == null) {
      return null;
    }
    MaterialPriceType existing = materialPriceTypeMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    materialPriceTypeMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && materialPriceTypeMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<MaterialPriceType> importItems(MaterialPriceTypeImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<MaterialPriceType> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null
          || !StringUtils.hasText(row.getBillNo())
          || !StringUtils.hasText(row.getMaterialCode())
          || !StringUtils.hasText(row.getPriceType())
          || !StringUtils.hasText(row.getPeriod())) {
        continue;
      }
      MaterialPriceType entity = new MaterialPriceType();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!StringUtils.hasText(entity.getBillNo())
          || !StringUtils.hasText(entity.getMaterialCode())
          || !StringUtils.hasText(entity.getPeriod())) {
        continue;
      }
      materialPriceTypeMapper.delete(
          Wrappers.lambdaQuery(MaterialPriceType.class)
              .eq(MaterialPriceType::getBillNo, entity.getBillNo())
              .eq(MaterialPriceType::getMaterialCode, entity.getMaterialCode())
              .eq(MaterialPriceType::getPeriod, entity.getPeriod()));
      materialPriceTypeMapper.insert(entity);
      imported.add(entity);
    }
    return imported;
  }

  private void fillFromRow(
      MaterialPriceType entity, MaterialPriceTypeImportRequest.MaterialPriceTypeRow row) {
    entity.setRowNo(row.getRowNo());
    entity.setBillNo(row.getBillNo());
    entity.setMaterialCode(row.getMaterialCode());
    entity.setMaterialName(row.getMaterialName());
    entity.setMaterialSpec(row.getMaterialSpec());
    entity.setMaterialModel(row.getMaterialModel());
    entity.setMaterialShape(row.getMaterialShape());
    entity.setPriceType(row.getPriceType());
    entity.setPeriod(row.getPeriod());
    entity.setSource(row.getSource());
  }

  private void merge(MaterialPriceType entity, MaterialPriceTypeRequest request) {
    if (request == null) {
      return;
    }
    if (request.getRowNo() != null) {
      entity.setRowNo(request.getRowNo());
    }
    if (request.getBillNo() != null) {
      entity.setBillNo(request.getBillNo());
    }
    if (request.getMaterialCode() != null) {
      entity.setMaterialCode(request.getMaterialCode());
    }
    if (request.getMaterialName() != null) {
      entity.setMaterialName(request.getMaterialName());
    }
    if (request.getMaterialSpec() != null) {
      entity.setMaterialSpec(request.getMaterialSpec());
    }
    if (request.getMaterialModel() != null) {
      entity.setMaterialModel(request.getMaterialModel());
    }
    if (request.getMaterialShape() != null) {
      entity.setMaterialShape(request.getMaterialShape());
    }
    if (request.getPriceType() != null) {
      entity.setPriceType(request.getPriceType());
    }
    if (request.getPeriod() != null) {
      entity.setPeriod(request.getPeriod());
    }
    if (request.getSource() != null) {
      entity.setSource(request.getSource());
    }
  }

  private void fillDefaults(MaterialPriceType entity) {
    if (!StringUtils.hasText(entity.getSource())) {
      entity.setSource(DEFAULT_SOURCE);
    }
    if (StringUtils.hasText(entity.getBillNo())) {
      entity.setBillNo(entity.getBillNo().trim());
    }
    if (StringUtils.hasText(entity.getMaterialCode())) {
      entity.setMaterialCode(entity.getMaterialCode().trim());
    }
    if (StringUtils.hasText(entity.getPriceType())) {
      entity.setPriceType(entity.getPriceType().trim());
    }
    if (StringUtils.hasText(entity.getPeriod())) {
      entity.setPeriod(entity.getPeriod().trim());
    }
    if (StringUtils.hasText(entity.getMaterialShape())) {
      entity.setMaterialShape(entity.getMaterialShape().trim());
    }
  }
}
