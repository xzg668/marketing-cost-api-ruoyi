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
    List<MaterialPriceType> currentVersions = findCurrentVersions(entity);
    MaterialPriceType samePriceType = findSamePriceType(currentVersions, entity.getPriceType());
    if (samePriceType != null) {
      overwriteExisting(samePriceType, entity);
      materialPriceTypeMapper.updateById(samePriceType);
      return samePriceType;
    }
    closePreviousVersions(currentVersions, entity.getEffectiveFrom());
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
    existing.setEffectiveTo(effectiveFrom.minusDays(1));
    materialPriceTypeMapper.updateById(existing);
    closePreviousVersions(findCurrentVersions(next), effectiveFrom, existing.getId());
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
          || !StringUtils.hasText(row.getPriceType())) {
        continue;
      }
      MaterialPriceType entity = new MaterialPriceType();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequiredBusinessFields(entity)) {
        continue;
      }
      List<MaterialPriceType> currentVersions = findCurrentVersions(entity);
      MaterialPriceType samePriceType = findSamePriceType(currentVersions, entity.getPriceType());
      if (samePriceType != null) {
        overwriteExisting(samePriceType, entity);
        materialPriceTypeMapper.updateById(samePriceType);
        imported.add(samePriceType);
        continue;
      }
      closePreviousVersions(currentVersions, entity.getEffectiveFrom());
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
    entity.setPriceType(normalizePriceType(row.getPriceType()));
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
      entity.setPriceType(normalizePriceType(request.getPriceType()));
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

  private List<MaterialPriceType> findCurrentVersions(MaterialPriceType entity) {
    if (entity == null || entity.getEffectiveFrom() == null || !StringUtils.hasText(entity.getMaterialCode())) {
      return List.of();
    }
    var query = Wrappers.lambdaQuery(MaterialPriceType.class)
        .eq(MaterialPriceType::getMaterialCode, entity.getMaterialCode())
        .eq(MaterialPriceType::getPriority, entity.getPriority())
        .and(q -> q.isNull(MaterialPriceType::getEffectiveTo)
            .or()
            .gt(MaterialPriceType::getEffectiveTo, entity.getEffectiveFrom()));
    return materialPriceTypeMapper.selectList(query);
  }

  private MaterialPriceType findSamePriceType(List<MaterialPriceType> rows, String priceType) {
    if (rows == null || !StringUtils.hasText(priceType)) {
      return null;
    }
    for (MaterialPriceType row : rows) {
      if (priceType.equals(normalizePriceType(row.getPriceType()))) {
        return row;
      }
    }
    return null;
  }

  private void overwriteExisting(MaterialPriceType target, MaterialPriceType source) {
    Long id = target.getId();
    LocalDate effectiveFrom = target.getEffectiveFrom();
    LocalDate effectiveTo = target.getEffectiveTo();
    fillImportFields(target, source);
    target.setId(id);
    target.setEffectiveFrom(effectiveFrom != null ? effectiveFrom : source.getEffectiveFrom());
    target.setEffectiveTo(effectiveTo);
  }

  private void fillImportFields(MaterialPriceType target, MaterialPriceType source) {
    if (source.getRowNo() != null) {
      target.setRowNo(source.getRowNo());
    }
    if (source.getBillNo() != null) {
      target.setBillNo(source.getBillNo());
    }
    target.setMaterialCode(source.getMaterialCode());
    if (source.getMaterialName() != null) {
      target.setMaterialName(source.getMaterialName());
    }
    if (source.getMaterialSpec() != null) {
      target.setMaterialSpec(source.getMaterialSpec());
    }
    if (source.getMaterialModel() != null) {
      target.setMaterialModel(source.getMaterialModel());
    }
    if (source.getUnit() != null) {
      target.setUnit(source.getUnit());
    }
    if (source.getMaterialShape() != null) {
      target.setMaterialShape(source.getMaterialShape());
    }
    if (source.getCategoryCode() != null) {
      target.setCategoryCode(source.getCategoryCode());
    }
    if (source.getCategoryName() != null) {
      target.setCategoryName(source.getCategoryName());
    }
    target.setPriceType(source.getPriceType());
    if (source.getPeriod() != null) {
      target.setPeriod(source.getPeriod());
    }
    if (source.getSource() != null) {
      target.setSource(source.getSource());
    }
    if (source.getPriority() != null) {
      target.setPriority(source.getPriority());
    }
  }

  private void closePreviousVersions(List<MaterialPriceType> rows, LocalDate effectiveFrom) {
    closePreviousVersions(rows, effectiveFrom, null);
  }

  private void closePreviousVersions(List<MaterialPriceType> rows, LocalDate effectiveFrom, Long excludedId) {
    if (rows == null || rows.isEmpty() || effectiveFrom == null) {
      return;
    }
    for (MaterialPriceType row : rows) {
      if (excludedId != null && excludedId.equals(row.getId())) {
        continue;
      }
      row.setEffectiveTo(effectiveFrom.minusDays(1));
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
        && StringUtils.hasText(entity.getPriceType());
  }

  private String normalizePriceType(String value) {
    if (!StringUtils.hasText(value)) {
      return value;
    }
    String text = value.trim();
    if ("固定采购价".equals(text) || "采购固定价".equals(text)) {
      return "固定价";
    }
    return text;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
