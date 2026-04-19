package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.AuxRateItemImportRequest;
import com.sanhua.marketingcost.dto.AuxRateItemRequest;
import com.sanhua.marketingcost.entity.AuxRateItem;
import com.sanhua.marketingcost.mapper.AuxRateItemMapper;
import com.sanhua.marketingcost.service.AuxRateItemService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuxRateItemServiceImpl implements AuxRateItemService {
  private static final String DEFAULT_SOURCE = "import";

  private final AuxRateItemMapper auxRateItemMapper;

  public AuxRateItemServiceImpl(AuxRateItemMapper auxRateItemMapper) {
    this.auxRateItemMapper = auxRateItemMapper;
  }

  @Override
  public Page<AuxRateItem> page(String materialCode, String period, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(AuxRateItem.class);
    if (StringUtils.hasText(materialCode)) {
      query.like(AuxRateItem::getMaterialCode, materialCode.trim());
    }
    if (StringUtils.hasText(period)) {
      query.eq(AuxRateItem::getPeriod, period.trim());
    }
    query.orderByDesc(AuxRateItem::getPeriod).orderByDesc(AuxRateItem::getId);
    Page<AuxRateItem> pager = new Page<>(page, pageSize);
    return auxRateItemMapper.selectPage(pager, query);
  }

  @Override
  public AuxRateItem create(AuxRateItemRequest request) {
    if (request == null) {
      return null;
    }
    AuxRateItem entity = new AuxRateItem();
    merge(entity, request);
    fillDefaults(entity);
    if (!StringUtils.hasText(entity.getMaterialCode()) || entity.getFloatRate() == null) {
      return null;
    }
    auxRateItemMapper.insert(entity);
    return entity;
  }

  @Override
  public AuxRateItem update(Long id, AuxRateItemRequest request) {
    if (id == null) {
      return null;
    }
    AuxRateItem existing = auxRateItemMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    auxRateItemMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && auxRateItemMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<AuxRateItem> importItems(AuxRateItemImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<AuxRateItem> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null
          || !StringUtils.hasText(row.getMaterialCode())
          || row.getFloatRate() == null) {
        continue;
      }
      AuxRateItem entity = new AuxRateItem();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!StringUtils.hasText(entity.getMaterialCode())) {
        continue;
      }
      var deleteQuery =
          Wrappers.lambdaQuery(AuxRateItem.class)
              .eq(AuxRateItem::getMaterialCode, entity.getMaterialCode());
      if (StringUtils.hasText(entity.getPeriod())) {
        deleteQuery.eq(AuxRateItem::getPeriod, entity.getPeriod());
      } else {
        deleteQuery.isNull(AuxRateItem::getPeriod);
      }
      auxRateItemMapper.delete(deleteQuery);
      auxRateItemMapper.insert(entity);
      imported.add(entity);
    }
    return imported;
  }

  private void fillFromRow(AuxRateItem entity, AuxRateItemImportRequest.AuxRateItemRow row) {
    entity.setMaterialCode(row.getMaterialCode());
    entity.setMaterialName(row.getMaterialName());
    entity.setSpec(row.getSpec());
    entity.setModel(row.getModel());
    entity.setFloatRate(row.getFloatRate());
    entity.setPeriod(row.getPeriod());
    entity.setSource(row.getSource());
  }

  private void merge(AuxRateItem entity, AuxRateItemRequest request) {
    if (request == null) {
      return;
    }
    if (request.getMaterialCode() != null) {
      entity.setMaterialCode(request.getMaterialCode());
    }
    if (request.getMaterialName() != null) {
      entity.setMaterialName(request.getMaterialName());
    }
    if (request.getSpec() != null) {
      entity.setSpec(request.getSpec());
    }
    if (request.getModel() != null) {
      entity.setModel(request.getModel());
    }
    if (request.getFloatRate() != null) {
      entity.setFloatRate(request.getFloatRate());
    }
    if (request.getPeriod() != null) {
      entity.setPeriod(request.getPeriod());
    }
    if (request.getSource() != null) {
      entity.setSource(request.getSource());
    }
  }

  private void fillDefaults(AuxRateItem entity) {
    if (!StringUtils.hasText(entity.getSource())) {
      entity.setSource(DEFAULT_SOURCE);
    }
    if (StringUtils.hasText(entity.getMaterialCode())) {
      entity.setMaterialCode(entity.getMaterialCode().trim());
    }
    if (StringUtils.hasText(entity.getPeriod())) {
      entity.setPeriod(entity.getPeriod().trim());
    } else {
      entity.setPeriod(null);
    }
  }
}
