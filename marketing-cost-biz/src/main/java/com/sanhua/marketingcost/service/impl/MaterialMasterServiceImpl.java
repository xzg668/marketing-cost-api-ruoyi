package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MaterialMasterImportRequest;
import com.sanhua.marketingcost.dto.MaterialMasterRequest;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.service.MaterialMasterService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialMasterServiceImpl implements MaterialMasterService {
  private static final String DEFAULT_SOURCE = "import";

  private final MaterialMasterMapper materialMasterMapper;

  public MaterialMasterServiceImpl(MaterialMasterMapper materialMasterMapper) {
    this.materialMasterMapper = materialMasterMapper;
  }

  @Override
  public Page<MaterialMaster> page(String materialCode, String materialName, String itemSpec,
      String itemModel, String drawingNo, String shapeAttr, String material, String bizUnit,
      String productionDept, String productionWorkshop, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(MaterialMaster.class);
    if (StringUtils.hasText(materialCode)) {
      query.like(MaterialMaster::getMaterialCode, materialCode.trim());
    }
    if (StringUtils.hasText(materialName)) {
      query.like(MaterialMaster::getMaterialName, materialName.trim());
    }
    if (StringUtils.hasText(itemSpec)) {
      query.like(MaterialMaster::getItemSpec, itemSpec.trim());
    }
    if (StringUtils.hasText(itemModel)) {
      query.like(MaterialMaster::getItemModel, itemModel.trim());
    }
    if (StringUtils.hasText(drawingNo)) {
      query.like(MaterialMaster::getDrawingNo, drawingNo.trim());
    }
    if (StringUtils.hasText(shapeAttr)) {
      query.eq(MaterialMaster::getShapeAttr, shapeAttr.trim());
    }
    if (StringUtils.hasText(material)) {
      query.like(MaterialMaster::getMaterial, material.trim());
    }
    if (StringUtils.hasText(bizUnit)) {
      query.like(MaterialMaster::getBizUnit, bizUnit.trim());
    }
    if (StringUtils.hasText(productionDept)) {
      query.like(MaterialMaster::getProductionDept, productionDept.trim());
    }
    if (StringUtils.hasText(productionWorkshop)) {
      query.like(MaterialMaster::getProductionWorkshop, productionWorkshop.trim());
    }
    query.orderByAsc(MaterialMaster::getMaterialCode).orderByAsc(MaterialMaster::getId);
    Page<MaterialMaster> pager = new Page<>(page, pageSize);
    return materialMasterMapper.selectPage(pager, query);
  }

  @Override
  public MaterialMaster create(MaterialMasterRequest request) {
    if (request == null) {
      return null;
    }
    MaterialMaster entity = new MaterialMaster();
    merge(entity, request);
    fillDefaults(entity);
    if (!StringUtils.hasText(entity.getMaterialCode())
        || !StringUtils.hasText(entity.getMaterialName())) {
      return null;
    }
    materialMasterMapper.insert(entity);
    return entity;
  }

  @Override
  public MaterialMaster update(Long id, MaterialMasterRequest request) {
    if (id == null) {
      return null;
    }
    MaterialMaster existing = materialMasterMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    materialMasterMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && materialMasterMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<MaterialMaster> importItems(MaterialMasterImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<MaterialMaster> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null
          || !StringUtils.hasText(row.getMaterialCode())
          || !StringUtils.hasText(row.getMaterialName())) {
        continue;
      }
      MaterialMaster existing = findExisting(row);
      MaterialMaster entity = existing != null ? existing : new MaterialMaster();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (existing == null) {
        materialMasterMapper.insert(entity);
      } else {
        materialMasterMapper.updateById(entity);
      }
      imported.add(entity);
    }
    return imported;
  }

  private MaterialMaster findExisting(MaterialMasterImportRequest.MaterialMasterRow row) {
    return materialMasterMapper.selectOne(Wrappers.lambdaQuery(MaterialMaster.class)
        .eq(MaterialMaster::getMaterialCode, row.getMaterialCode().trim())
        .last("LIMIT 1"));
  }

  private void fillFromRow(MaterialMaster entity, MaterialMasterImportRequest.MaterialMasterRow row) {
    entity.setMaterialCode(row.getMaterialCode());
    entity.setMaterialName(row.getMaterialName());
    entity.setItemSpec(row.getItemSpec());
    entity.setItemModel(row.getItemModel());
    entity.setDrawingNo(row.getDrawingNo());
    entity.setShapeAttr(row.getShapeAttr());
    entity.setMaterial(row.getMaterial());
    entity.setTheoreticalWeightG(row.getTheoreticalWeightG());
    entity.setNetWeightKg(row.getNetWeightKg());
    entity.setBizUnit(row.getBizUnit());
    entity.setProductionDept(row.getProductionDept());
    entity.setProductionWorkshop(row.getProductionWorkshop());
    entity.setSource(row.getSource());
  }

  private void merge(MaterialMaster entity, MaterialMasterRequest request) {
    if (request == null) {
      return;
    }
    if (request.getMaterialCode() != null) {
      entity.setMaterialCode(request.getMaterialCode());
    }
    if (request.getMaterialName() != null) {
      entity.setMaterialName(request.getMaterialName());
    }
    if (request.getItemSpec() != null) {
      entity.setItemSpec(request.getItemSpec());
    }
    if (request.getItemModel() != null) {
      entity.setItemModel(request.getItemModel());
    }
    if (request.getDrawingNo() != null) {
      entity.setDrawingNo(request.getDrawingNo());
    }
    if (request.getShapeAttr() != null) {
      entity.setShapeAttr(request.getShapeAttr());
    }
    if (request.getMaterial() != null) {
      entity.setMaterial(request.getMaterial());
    }
    if (request.getTheoreticalWeightG() != null) {
      entity.setTheoreticalWeightG(request.getTheoreticalWeightG());
    }
    if (request.getNetWeightKg() != null) {
      entity.setNetWeightKg(request.getNetWeightKg());
    }
    if (request.getBizUnit() != null) {
      entity.setBizUnit(request.getBizUnit());
    }
    if (request.getProductionDept() != null) {
      entity.setProductionDept(request.getProductionDept());
    }
    if (request.getProductionWorkshop() != null) {
      entity.setProductionWorkshop(request.getProductionWorkshop());
    }
    if (request.getSource() != null) {
      entity.setSource(request.getSource());
    }
  }

  private void fillDefaults(MaterialMaster entity) {
    if (!StringUtils.hasText(entity.getSource())) {
      entity.setSource(DEFAULT_SOURCE);
    }
    if (StringUtils.hasText(entity.getMaterialCode())) {
      entity.setMaterialCode(entity.getMaterialCode().trim());
    }
    if (StringUtils.hasText(entity.getMaterialName())) {
      entity.setMaterialName(entity.getMaterialName().trim());
    }
    if (StringUtils.hasText(entity.getItemSpec())) {
      entity.setItemSpec(entity.getItemSpec().trim());
    }
    if (StringUtils.hasText(entity.getItemModel())) {
      entity.setItemModel(entity.getItemModel().trim());
    }
    if (StringUtils.hasText(entity.getDrawingNo())) {
      entity.setDrawingNo(entity.getDrawingNo().trim());
    }
    if (StringUtils.hasText(entity.getShapeAttr())) {
      entity.setShapeAttr(entity.getShapeAttr().trim());
    }
    if (StringUtils.hasText(entity.getMaterial())) {
      entity.setMaterial(entity.getMaterial().trim());
    }
    if (StringUtils.hasText(entity.getBizUnit())) {
      entity.setBizUnit(entity.getBizUnit().trim());
    }
    if (StringUtils.hasText(entity.getProductionDept())) {
      entity.setProductionDept(entity.getProductionDept().trim());
    }
    if (StringUtils.hasText(entity.getProductionWorkshop())) {
      entity.setProductionWorkshop(entity.getProductionWorkshop().trim());
    }
  }
}
