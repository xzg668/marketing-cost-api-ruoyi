package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MaterialPriceTypeImportRequest;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 物料价格类型人工维护。
 *
 * <p>价格类型采用追加历史：当前类型按 created_at、id 倒序确定；相同类型重复导入不更新、
 * 不新增，类型变化才新增一条。正式价格入口复用 MaterialPriceTypeRouteSyncService。
 */
@Service
public class MaterialPriceTypeServiceImpl implements MaterialPriceTypeService {
  private static final String DEFAULT_SOURCE = "import";

  private final MaterialPriceTypeMapper mapper;

  public MaterialPriceTypeServiceImpl(MaterialPriceTypeMapper mapper) {
    this.mapper = mapper;
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
        .orderByDesc(MaterialPriceType::getCreatedAt)
        .orderByDesc(MaterialPriceType::getId);
    return mapper.selectPage(new Page<>(page, pageSize), query);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MaterialPriceType create(MaterialPriceTypeRequest request) {
    if (request == null) {
      return null;
    }
    MaterialPriceType entity = new MaterialPriceType();
    merge(entity, request);
    fillDefaults(entity);
    return saveIfTypeChanged(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MaterialPriceType update(Long id, MaterialPriceTypeRequest request) {
    if (id == null || request == null) {
      return null;
    }
    MaterialPriceType existing = mapper.selectById(id);
    if (existing == null) {
      return null;
    }
    MaterialPriceType next = copyOf(existing);
    merge(next, request);
    fillDefaults(next);
    return saveIfTypeChanged(next);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean delete(Long id) {
    return id != null && mapper.deleteById(id) > 0;
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
      MaterialPriceType saved = saveIfTypeChanged(entity);
      if (saved != null) {
        imported.add(saved);
      }
    }
    return imported;
  }

  private MaterialPriceType saveIfTypeChanged(MaterialPriceType entity) {
    if (!hasRequiredFields(entity)) {
      return null;
    }
    MaterialPriceType current = findCurrent(entity.getMaterialCode());
    if (current != null
        && Objects.equals(PriceTypeEnum.normalizeRouteText(current.getPriceType()), entity.getPriceType())) {
      return current;
    }
    entity.setId(null);
    mapper.insert(entity);
    return entity;
  }

  private MaterialPriceType findCurrent(String materialCode) {
    List<MaterialPriceType> rows = mapper.selectList(
        Wrappers.lambdaQuery(MaterialPriceType.class)
            .eq(MaterialPriceType::getMaterialCode, materialCode)
            .orderByDesc(MaterialPriceType::getCreatedAt)
            .orderByDesc(MaterialPriceType::getId)
            .last("LIMIT 1 FOR UPDATE"));
    return rows == null || rows.isEmpty() ? null : rows.getFirst();
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
    if (request.getRowNo() != null) entity.setRowNo(request.getRowNo());
    if (request.getBillNo() != null) entity.setBillNo(request.getBillNo());
    if (request.getMaterialCode() != null) entity.setMaterialCode(request.getMaterialCode());
    if (request.getMaterialName() != null) entity.setMaterialName(request.getMaterialName());
    if (request.getMaterialSpec() != null) entity.setMaterialSpec(request.getMaterialSpec());
    if (request.getMaterialModel() != null) entity.setMaterialModel(request.getMaterialModel());
    if (request.getUnit() != null) entity.setUnit(request.getUnit());
    if (request.getMaterialShape() != null) entity.setMaterialShape(request.getMaterialShape());
    if (request.getCategoryCode() != null) entity.setCategoryCode(request.getCategoryCode());
    if (request.getCategoryName() != null) entity.setCategoryName(request.getCategoryName());
    if (request.getPriceType() != null) entity.setPriceType(request.getPriceType());
    if (request.getPeriod() != null) entity.setPeriod(request.getPeriod());
    if (request.getSource() != null) entity.setSource(request.getSource());
  }

  private void fillDefaults(MaterialPriceType entity) {
    entity.setBillNo(trimToNull(entity.getBillNo()));
    entity.setMaterialCode(trimToNull(entity.getMaterialCode()));
    entity.setMaterialName(trimToNull(entity.getMaterialName()));
    entity.setMaterialSpec(trimToNull(entity.getMaterialSpec()));
    entity.setMaterialModel(trimToNull(entity.getMaterialModel()));
    entity.setUnit(trimToNull(entity.getUnit()));
    entity.setMaterialShape(trimToNull(entity.getMaterialShape()));
    entity.setCategoryCode(trimToNull(entity.getCategoryCode()));
    entity.setCategoryName(trimToNull(entity.getCategoryName()));
    entity.setPriceType(PriceTypeEnum.normalizeRouteText(entity.getPriceType()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
    entity.setSource(firstText(entity.getSource(), DEFAULT_SOURCE));
    if (entity.getPriority() == null) entity.setPriority(1);
    if (entity.getEffectiveFrom() == null) {
      entity.setEffectiveFrom(defaultEffectiveFrom(entity.getPeriod()));
    }
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
    copy.setSourceSystem(source.getSourceSystem());
    copy.setBusinessUnitType(source.getBusinessUnitType());
    return copy;
  }

  private boolean hasRequiredFields(MaterialPriceType entity) {
    return entity != null
        && StringUtils.hasText(entity.getMaterialCode())
        && StringUtils.hasText(entity.getPriceType());
  }

  private LocalDate defaultEffectiveFrom(String period) {
    if (StringUtils.hasText(period)) {
      try {
        return YearMonth.parse(period.trim()).atDay(1);
      } catch (java.time.format.DateTimeParseException ignored) {
        // 非年月格式按今天开始，仅保留历史字段兼容。
      }
    }
    return LocalDate.now();
  }

  private String firstText(String... values) {
    for (String value : values) {
      String normalized = trimToNull(value);
      if (normalized != null) return normalized;
    }
    return null;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
