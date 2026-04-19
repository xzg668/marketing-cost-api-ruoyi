package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.AuxSubjectImportRequest;
import com.sanhua.marketingcost.dto.AuxSubjectRequest;
import com.sanhua.marketingcost.entity.AuxSubject;
import com.sanhua.marketingcost.mapper.AuxSubjectMapper;
import com.sanhua.marketingcost.service.AuxSubjectService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuxSubjectServiceImpl implements AuxSubjectService {
  private static final String DEFAULT_SOURCE = "import";

  private final AuxSubjectMapper auxSubjectMapper;

  public AuxSubjectServiceImpl(AuxSubjectMapper auxSubjectMapper) {
    this.auxSubjectMapper = auxSubjectMapper;
  }

  @Override
  public Page<AuxSubject> page(String materialCode, String auxSubjectCode, String period,
      int page, int pageSize) {
    var query = Wrappers.lambdaQuery(AuxSubject.class);
    if (StringUtils.hasText(materialCode)) {
      query.like(AuxSubject::getMaterialCode, materialCode.trim());
    }
    if (StringUtils.hasText(auxSubjectCode)) {
      query.like(AuxSubject::getAuxSubjectCode, auxSubjectCode.trim());
    }
    if (StringUtils.hasText(period)) {
      query.eq(AuxSubject::getPeriod, period.trim());
    }
    query.orderByDesc(AuxSubject::getPeriod).orderByDesc(AuxSubject::getId);
    Page<AuxSubject> pager = new Page<>(page, pageSize);
    return auxSubjectMapper.selectPage(pager, query);
  }

  @Override
  public AuxSubject create(AuxSubjectRequest request) {
    if (request == null) {
      return null;
    }
    AuxSubject entity = new AuxSubject();
    merge(entity, request);
    fillDefaults(entity);
    if (!StringUtils.hasText(entity.getMaterialCode())
        || !StringUtils.hasText(entity.getAuxSubjectCode())
        || !StringUtils.hasText(entity.getAuxSubjectName())
        || !StringUtils.hasText(entity.getPeriod())) {
      return null;
    }
    tryFillUnitPriceFromRef(
        entity, entity.getRefMaterialCode(), entity.getAuxSubjectCode(), entity.getPeriod());
    auxSubjectMapper.insert(entity);
    return entity;
  }

  @Override
  public AuxSubject update(Long id, AuxSubjectRequest request) {
    if (id == null) {
      return null;
    }
    AuxSubject existing = auxSubjectMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    tryFillUnitPriceFromRef(
        existing, existing.getRefMaterialCode(), existing.getAuxSubjectCode(), existing.getPeriod());
    auxSubjectMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && auxSubjectMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<AuxSubject> importItems(AuxSubjectImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    Map<String, java.math.BigDecimal> importPriceMap = new HashMap<>();
    for (var row : request.getRows()) {
      if (row == null || row.getUnitPrice() == null) {
        continue;
      }
      if (!StringUtils.hasText(row.getMaterialCode())
          || !StringUtils.hasText(row.getAuxSubjectCode())
          || !StringUtils.hasText(row.getPeriod())) {
        continue;
      }
      importPriceMap.put(
          buildPriceKey(row.getMaterialCode(), row.getAuxSubjectCode(), row.getPeriod()),
          row.getUnitPrice());
    }
    List<AuxSubject> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null
          || !StringUtils.hasText(row.getMaterialCode())
          || !StringUtils.hasText(row.getAuxSubjectCode())
          || !StringUtils.hasText(row.getAuxSubjectName())
          || !StringUtils.hasText(row.getPeriod())) {
        continue;
      }
      AuxSubject existing = findExisting(row);
      AuxSubject entity = existing != null ? existing : new AuxSubject();
      fillFromRow(entity, row);
      fillDefaults(entity);
      tryFillUnitPriceFromImport(entity, row, importPriceMap);
      tryFillUnitPriceFromRef(entity, row.getRefMaterialCode(), row.getAuxSubjectCode(),
          row.getPeriod());
      if (existing == null) {
        auxSubjectMapper.insert(entity);
      } else {
        auxSubjectMapper.updateById(entity);
      }
      imported.add(entity);
    }
    return imported;
  }

  @Override
  public java.math.BigDecimal quoteUnitPrice(
      String refMaterialCode, String auxSubjectCode, String period) {
    if (!StringUtils.hasText(refMaterialCode)
        || !StringUtils.hasText(auxSubjectCode)
        || !StringUtils.hasText(period)) {
      return null;
    }
    AuxSubject record = auxSubjectMapper.selectOne(Wrappers.lambdaQuery(AuxSubject.class)
        .select(AuxSubject::getUnitPrice)
        .eq(AuxSubject::getMaterialCode, refMaterialCode.trim())
        .eq(AuxSubject::getAuxSubjectCode, auxSubjectCode.trim())
        .eq(AuxSubject::getPeriod, period.trim())
        .last("LIMIT 1"));
    return record == null ? null : record.getUnitPrice();
  }

  private AuxSubject findExisting(AuxSubjectImportRequest.AuxSubjectRow row) {
    String refMaterialCode =
        StringUtils.hasText(row.getRefMaterialCode()) ? row.getRefMaterialCode().trim() : null;
    var query = Wrappers.lambdaQuery(AuxSubject.class)
        .eq(AuxSubject::getMaterialCode, row.getMaterialCode().trim())
        .eq(AuxSubject::getAuxSubjectCode, row.getAuxSubjectCode().trim())
        .eq(AuxSubject::getPeriod, row.getPeriod().trim());
    if (refMaterialCode != null) {
      query.eq(AuxSubject::getRefMaterialCode, refMaterialCode);
    } else {
      query.and(
          wrapper -> wrapper.isNull(AuxSubject::getRefMaterialCode)
              .or()
              .eq(AuxSubject::getRefMaterialCode, ""));
    }
    return auxSubjectMapper.selectOne(query.last("LIMIT 1"));
  }

  private void fillFromRow(AuxSubject entity, AuxSubjectImportRequest.AuxSubjectRow row) {
    entity.setMaterialCode(row.getMaterialCode());
    entity.setProductName(row.getProductName());
    entity.setSpec(row.getSpec());
    entity.setModel(row.getModel());
    entity.setRefMaterialCode(row.getRefMaterialCode());
    entity.setAuxSubjectCode(row.getAuxSubjectCode());
    entity.setAuxSubjectName(row.getAuxSubjectName());
    if (row.getUnitPrice() != null) {
      entity.setUnitPrice(row.getUnitPrice());
    }
    entity.setPeriod(row.getPeriod());
    entity.setSource(row.getSource());
  }

  private void merge(AuxSubject entity, AuxSubjectRequest request) {
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
    if (request.getAuxSubjectCode() != null) {
      entity.setAuxSubjectCode(request.getAuxSubjectCode());
    }
    if (request.getAuxSubjectName() != null) {
      entity.setAuxSubjectName(request.getAuxSubjectName());
    }
    if (request.getUnitPrice() != null) {
      entity.setUnitPrice(request.getUnitPrice());
    }
    if (request.getPeriod() != null) {
      entity.setPeriod(request.getPeriod());
    }
    if (request.getSource() != null) {
      entity.setSource(request.getSource());
    }
  }

  private void fillDefaults(AuxSubject entity) {
    if (!StringUtils.hasText(entity.getSource())) {
      entity.setSource(DEFAULT_SOURCE);
    }
    if (StringUtils.hasText(entity.getMaterialCode())) {
      entity.setMaterialCode(entity.getMaterialCode().trim());
    }
    if (StringUtils.hasText(entity.getAuxSubjectCode())) {
      entity.setAuxSubjectCode(entity.getAuxSubjectCode().trim());
    }
    if (StringUtils.hasText(entity.getAuxSubjectName())) {
      entity.setAuxSubjectName(entity.getAuxSubjectName().trim());
    }
    if (StringUtils.hasText(entity.getRefMaterialCode())) {
      entity.setRefMaterialCode(entity.getRefMaterialCode().trim());
    } else {
      entity.setRefMaterialCode(null);
    }
    if (StringUtils.hasText(entity.getPeriod())) {
      entity.setPeriod(entity.getPeriod().trim());
    }
  }

  private void tryFillUnitPriceFromRef(
      AuxSubject entity, String refMaterialCode, String auxSubjectCode, String period) {
    if (entity == null || entity.getUnitPrice() != null) {
      return;
    }
    if (!StringUtils.hasText(refMaterialCode)
        || !StringUtils.hasText(auxSubjectCode)
        || !StringUtils.hasText(period)) {
      return;
    }
    var quoted = quoteUnitPrice(refMaterialCode, auxSubjectCode, period);
    if (quoted != null) {
      entity.setUnitPrice(quoted);
    }
  }

  private void tryFillUnitPriceFromImport(
      AuxSubject entity,
      AuxSubjectImportRequest.AuxSubjectRow row,
      Map<String, java.math.BigDecimal> importPriceMap) {
    if (entity == null || entity.getUnitPrice() != null || row == null) {
      return;
    }
    if (!StringUtils.hasText(row.getRefMaterialCode())
        || !StringUtils.hasText(row.getAuxSubjectCode())
        || !StringUtils.hasText(row.getPeriod())) {
      return;
    }
    var key = buildPriceKey(row.getRefMaterialCode(), row.getAuxSubjectCode(), row.getPeriod());
    var quoted = importPriceMap.get(key);
    if (quoted != null) {
      entity.setUnitPrice(quoted);
    }
  }

  private String buildPriceKey(String materialCode, String auxSubjectCode, String period) {
    return String.format("%s|%s|%s", materialCode.trim(), auxSubjectCode.trim(), period.trim());
  }
}
