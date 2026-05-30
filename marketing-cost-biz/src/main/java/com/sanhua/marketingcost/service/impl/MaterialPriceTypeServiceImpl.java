package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MaterialPriceTypeImportRequest;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
import java.time.LocalDate;
import java.time.YearMonth;
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
    query.orderByAsc(MaterialPriceType::getMaterialCode)
        .orderByAsc(MaterialPriceType::getPriceType)
        .orderByDesc(MaterialPriceType::getId);
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
    if (!hasRequiredBusinessFields(entity)) {
      return null;
    }
    closePreviousVersions(entity);
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
    MaterialPriceType next = copyOf(existing);
    next.setId(null);
    merge(next, request);
    next.setEffectiveFrom(LocalDate.now());
    next.setEffectiveTo(null);
    fillDefaults(next);
    LocalDate effectiveFrom = next.getEffectiveFrom();
    existing.setEffectiveTo(effectiveFrom);
    materialPriceTypeMapper.updateById(existing);
    closePreviousVersions(next);
    materialPriceTypeMapper.insert(next);
    return next;
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
          || !StringUtils.hasText(row.getMaterialCode())
          || !StringUtils.hasText(row.getMaterialName())
          || !StringUtils.hasText(row.getMaterialModel())
          || !StringUtils.hasText(row.getMaterialShape())
          || !StringUtils.hasText(row.getPriceType())) {
        continue;
      }
      MaterialPriceType entity = new MaterialPriceType();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequiredBusinessFields(entity)) {
        continue;
      }
      closePreviousVersions(entity);
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
    entity.setUnit(row.getUnit());
    entity.setMaterialShape(row.getMaterialShape());
    entity.setCategoryCode(row.getCategoryCode());
    entity.setCategoryName(row.getCategoryName());
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
    if (request.getUnit() != null) {
      entity.setUnit(request.getUnit());
    }
    if (request.getMaterialShape() != null) {
      entity.setMaterialShape(request.getMaterialShape());
    }
    if (request.getCategoryCode() != null) {
      entity.setCategoryCode(request.getCategoryCode());
    }
    if (request.getCategoryName() != null) {
      entity.setCategoryName(request.getCategoryName());
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
    entity.setBillNo(trimToNull(entity.getBillNo()));
    entity.setMaterialCode(trimToNull(entity.getMaterialCode()));
    entity.setMaterialName(trimToNull(entity.getMaterialName()));
    entity.setMaterialSpec(trimToNull(entity.getMaterialSpec()));
    entity.setMaterialModel(trimToNull(entity.getMaterialModel()));
    entity.setUnit(trimToNull(entity.getUnit()));
    entity.setMaterialShape(trimToNull(entity.getMaterialShape()));
    entity.setCategoryCode(trimToNull(entity.getCategoryCode()));
    entity.setCategoryName(trimToNull(entity.getCategoryName()));
    entity.setPriceType(trimToNull(entity.getPriceType()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
    if (entity.getPriority() == null) {
      entity.setPriority(1);
    }
    if (entity.getEffectiveFrom() == null) {
      entity.setEffectiveFrom(defaultEffectiveFrom(entity.getPeriod()));
    }
  }

  private void closePreviousVersions(MaterialPriceType entity) {
    if (entity == null || entity.getEffectiveFrom() == null || !StringUtils.hasText(entity.getMaterialCode())) {
      return;
    }
    var query = Wrappers.lambdaQuery(MaterialPriceType.class)
        .eq(MaterialPriceType::getMaterialCode, entity.getMaterialCode())
        .eq(MaterialPriceType::getPriority, entity.getPriority())
        .and(q -> q.isNull(MaterialPriceType::getEffectiveTo)
            .or()
            .gt(MaterialPriceType::getEffectiveTo, entity.getEffectiveFrom()));
    if (StringUtils.hasText(entity.getMaterialModel())) {
      query.eq(MaterialPriceType::getMaterialModel, entity.getMaterialModel());
    } else {
      query.and(q -> q.isNull(MaterialPriceType::getMaterialModel)
          .or()
          .eq(MaterialPriceType::getMaterialModel, ""));
    }
    if (StringUtils.hasText(entity.getPeriod())) {
      query.eq(MaterialPriceType::getPeriod, entity.getPeriod());
    } else {
      query.and(q -> q.isNull(MaterialPriceType::getPeriod).or().eq(MaterialPriceType::getPeriod, ""));
    }
    List<MaterialPriceType> rows = materialPriceTypeMapper.selectList(query);
    for (MaterialPriceType row : rows) {
      if (entity.getId() != null && entity.getId().equals(row.getId())) {
        continue;
      }
      row.setEffectiveTo(entity.getEffectiveFrom());
      materialPriceTypeMapper.updateById(row);
    }
  }

  private LocalDate defaultEffectiveFrom(String period) {
    if (StringUtils.hasText(period)) {
      try {
        return YearMonth.parse(period.trim()).atDay(1);
      } catch (java.time.format.DateTimeParseException ignored) {
        // fall through
      }
    }
    return LocalDate.now();
  }

  private MaterialPriceType copyOf(MaterialPriceType source) {
    MaterialPriceType copy = new MaterialPriceType();
    copy.setRowNo(source.getRowNo());
    copy.setBillNo(source.getBillNo());
    copy.setMaterialCode(source.getMaterialCode());
    copy.setMaterialName(source.getMaterialName());
    copy.setMaterialSpec(source.getMaterialSpec());
    copy.setMaterialModel(source.getMaterialModel());
    copy.setUnit(source.getUnit());
    copy.setMaterialShape(source.getMaterialShape());
    copy.setCategoryCode(source.getCategoryCode());
    copy.setCategoryName(source.getCategoryName());
    copy.setPriceType(source.getPriceType());
    copy.setPeriod(source.getPeriod());
    copy.setSource(source.getSource());
    copy.setPriority(source.getPriority());
    copy.setEffectiveFrom(source.getEffectiveFrom());
    copy.setEffectiveTo(null);
    copy.setSourceSystem(source.getSourceSystem());
    copy.setBusinessUnitType(source.getBusinessUnitType());
    return copy;
  }

  private boolean hasRequiredBusinessFields(MaterialPriceType entity) {
    return entity != null
        && StringUtils.hasText(entity.getMaterialCode())
        && StringUtils.hasText(entity.getMaterialName())
        && StringUtils.hasText(entity.getMaterialModel())
        && StringUtils.hasText(entity.getMaterialShape())
        && StringUtils.hasText(entity.getPriceType());
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
