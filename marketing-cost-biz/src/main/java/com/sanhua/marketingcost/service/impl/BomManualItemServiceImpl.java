package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.BomManualItemImportRequest;
import com.sanhua.marketingcost.dto.BomManualItemRequest;
import com.sanhua.marketingcost.dto.BomManualSummaryRow;
import com.sanhua.marketingcost.entity.BomManualItem;
import com.sanhua.marketingcost.mapper.BomManualItemMapper;
import com.sanhua.marketingcost.service.BomManualItemService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BomManualItemServiceImpl implements BomManualItemService {
  private static final String DEFAULT_SOURCE = "import";
  private static final String DEFAULT_SHAPE_ATTR = "制造件";

  private final BomManualItemMapper bomManualItemMapper;

  public BomManualItemServiceImpl(BomManualItemMapper bomManualItemMapper) {
    this.bomManualItemMapper = bomManualItemMapper;
  }

  @Override
  public List<BomManualItem> list(
      String bomCode, String itemCode, String parentCode, Integer bomLevel, String shapeAttr) {
    var query = Wrappers.lambdaQuery(BomManualItem.class);
    if (StringUtils.hasText(bomCode)) {
      query.like(BomManualItem::getBomCode, bomCode.trim());
    }
    if (StringUtils.hasText(itemCode)) {
      query.like(BomManualItem::getItemCode, itemCode.trim());
    }
    if (StringUtils.hasText(parentCode)) {
      query.like(BomManualItem::getParentCode, parentCode.trim());
    }
    if (bomLevel != null) {
      query.eq(BomManualItem::getBomLevel, bomLevel);
    }
    if (StringUtils.hasText(shapeAttr)) {
      query.eq(BomManualItem::getShapeAttr, shapeAttr.trim());
    }
    query.orderByAsc(BomManualItem::getBomCode).orderByAsc(BomManualItem::getId);
    return bomManualItemMapper.selectList(query);
  }

  @Override
  public Page<BomManualItem> page(String bomCode, String itemCode, String parentCode,
      Integer bomLevel, String shapeAttr, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(BomManualItem.class);
    if (StringUtils.hasText(bomCode)) {
      query.like(BomManualItem::getBomCode, bomCode.trim());
    }
    if (StringUtils.hasText(itemCode)) {
      query.like(BomManualItem::getItemCode, itemCode.trim());
    }
    if (StringUtils.hasText(parentCode)) {
      query.like(BomManualItem::getParentCode, parentCode.trim());
    }
    if (bomLevel != null) {
      query.eq(BomManualItem::getBomLevel, bomLevel);
    }
    if (StringUtils.hasText(shapeAttr)) {
      query.eq(BomManualItem::getShapeAttr, shapeAttr.trim());
    }
    query.orderByAsc(BomManualItem::getBomCode).orderByAsc(BomManualItem::getId);
    Page<BomManualItem> pager = new Page<>(page, pageSize);
    return bomManualItemMapper.selectPage(pager, query);
  }

  @Override
  public Page<BomManualSummaryRow> summaryPage(String bomCode, String itemCode, String parentCode,
      Integer bomLevel, String shapeAttr, int page, int pageSize) {
    int current = Math.max(page, 1);
    int size = Math.max(pageSize, 1);
    String bomCodeFilter = trimToNull(bomCode);
    String itemCodeFilter = trimToNull(itemCode);
    String parentCodeFilter = trimToNull(parentCode);
    String shapeAttrFilter = trimToNull(shapeAttr);
    long total = Objects.requireNonNullElse(
        bomManualItemMapper.countSummaryRows(
            bomCodeFilter, itemCodeFilter, parentCodeFilter, bomLevel, shapeAttrFilter),
        0L);
    Page<BomManualSummaryRow> pager = new Page<>(current, size);
    pager.setTotal(total);
    if (total <= 0) {
      pager.setRecords(List.of());
      return pager;
    }
    long offset = (long) (current - 1) * size;
    List<BomManualSummaryRow> rows = bomManualItemMapper.selectSummaryRows(
        bomCodeFilter, itemCodeFilter, parentCodeFilter, bomLevel, shapeAttrFilter, offset, size);
    pager.setRecords(rows == null ? List.of() : rows);
    return pager;
  }

  @Override
  public List<BomManualItem> listByBomCode(
      String bomCode, String itemCode, String parentCode, Integer bomLevel, String shapeAttr) {
    String bomCodeFilter = trimToNull(bomCode);
    if (!StringUtils.hasText(bomCodeFilter)) {
      return List.of();
    }
    List<BomManualItem> rows = bomManualItemMapper.selectByBomCodeWithFilters(
        bomCodeFilter, trimToNull(itemCode), trimToNull(parentCode), bomLevel, trimToNull(shapeAttr));
    return rows == null ? List.of() : rows;
  }

  @Override
  public BomManualItem create(BomManualItemRequest request) {
    if (request == null) {
      return null;
    }
    BomManualItem entity = new BomManualItem();
    merge(entity, request);
    fillDefaults(entity);
    if (!StringUtils.hasText(entity.getBomCode())
        || !StringUtils.hasText(entity.getItemCode())
        || entity.getBomLevel() == null) {
      return null;
    }
    bomManualItemMapper.insert(entity);
    return entity;
  }

  @Override
  public BomManualItem update(Long id, BomManualItemRequest request) {
    if (id == null) {
      return null;
    }
    BomManualItem existing = bomManualItemMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    bomManualItemMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && bomManualItemMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<BomManualItem> importItems(BomManualItemImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<BomManualItem> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null || !StringUtils.hasText(row.getItemCode())
          || !StringUtils.hasText(row.getBomCode())
          || row.getBomLevel() == null || row.getBomLevel() < 1) {
        continue;
      }
      BomManualItem existing = findExisting(row);
      BomManualItem entity = existing != null ? existing : new BomManualItem();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (existing == null) {
        bomManualItemMapper.insert(entity);
      } else {
        bomManualItemMapper.updateById(entity);
      }
      imported.add(entity);
    }
    return imported;
  }

  private BomManualItem findExisting(BomManualItemImportRequest.BomManualItemRow row) {
    if (row == null) {
      return null;
    }
    // Idempotent import key: bom_code + item_code + bom_level.
    return bomManualItemMapper.selectOne(Wrappers.lambdaQuery(BomManualItem.class)
        .eq(BomManualItem::getBomCode, row.getBomCode().trim())
        .eq(BomManualItem::getItemCode, row.getItemCode().trim())
        .eq(BomManualItem::getBomLevel, row.getBomLevel())
        .last("LIMIT 1"));
  }

  private void fillFromRow(
      BomManualItem entity, BomManualItemImportRequest.BomManualItemRow row) {
    entity.setBomCode(row.getBomCode().trim());
    entity.setItemCode(row.getItemCode().trim());
    entity.setItemName(row.getItemName());
    entity.setItemSpec(row.getItemSpec());
    entity.setItemModel(row.getItemModel());
    entity.setBomLevel(row.getBomLevel());
    entity.setParentCode(trimToNull(row.getParentCode()));
    entity.setShapeAttr(row.getShapeAttr());
    entity.setBomQty(row.getBomQty());
    entity.setMaterial(row.getMaterial());
    entity.setSource(row.getSource());
  }

  private void merge(BomManualItem entity, BomManualItemRequest request) {
    if (request == null) {
      return;
    }
    if (request.getItemCode() != null) {
      entity.setItemCode(request.getItemCode());
    }
    if (request.getBomCode() != null) {
      entity.setBomCode(request.getBomCode());
    }
    if (request.getItemName() != null) {
      entity.setItemName(request.getItemName());
    }
    if (request.getItemSpec() != null) {
      entity.setItemSpec(request.getItemSpec());
    }
    if (request.getItemModel() != null) {
      entity.setItemModel(request.getItemModel());
    }
    if (request.getBomLevel() != null) {
      entity.setBomLevel(request.getBomLevel());
    }
    if (request.getParentCode() != null) {
      entity.setParentCode(trimToNull(request.getParentCode()));
    }
    if (request.getShapeAttr() != null) {
      entity.setShapeAttr(request.getShapeAttr());
    }
    if (request.getBomQty() != null) {
      entity.setBomQty(request.getBomQty());
    }
    if (request.getMaterial() != null) {
      entity.setMaterial(request.getMaterial());
    }
    if (request.getSource() != null) {
      entity.setSource(request.getSource());
    }
  }

  private void fillDefaults(BomManualItem entity) {
    if (!StringUtils.hasText(entity.getSource())) {
      entity.setSource(DEFAULT_SOURCE);
    }
    if (!StringUtils.hasText(entity.getShapeAttr())) {
      entity.setShapeAttr(DEFAULT_SHAPE_ATTR);
    }
    if (StringUtils.hasText(entity.getItemCode())) {
      entity.setItemCode(entity.getItemCode().trim());
    }
    if (StringUtils.hasText(entity.getBomCode())) {
      entity.setBomCode(entity.getBomCode().trim());
    }
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
