package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MakePartSpecImportRequest;
import com.sanhua.marketingcost.dto.MakePartSpecUpdateRequest;
import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.mapper.MakePartSpecMapper;
import com.sanhua.marketingcost.service.MakePartSpecService;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 自制件工艺规格 service 实现 (V48) */
@Service
public class MakePartSpecServiceImpl implements MakePartSpecService {

  /** 导入未指定 period 时默认当月（YYYY-MM）；T08 起改成动态，原来写死 "2026-03" 已过时 */
  private static String defaultPeriod() {
    return YearMonth.now().toString();
  }

  private final MakePartSpecMapper specMapper;

  public MakePartSpecServiceImpl(MakePartSpecMapper specMapper) {
    this.specMapper = specMapper;
  }

  @Override
  public Page<MakePartSpec> page(String materialCode, String period, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(MakePartSpec.class);
    if (StringUtils.hasText(materialCode)) {
      query.like(MakePartSpec::getMaterialCode, materialCode.trim());
    }
    if (StringUtils.hasText(period)) {
      query.eq(MakePartSpec::getPeriod, period.trim());
    }
    query.orderByDesc(MakePartSpec::getId);
    Page<MakePartSpec> pager = new Page<>(page, pageSize);
    return specMapper.selectPage(pager, query);
  }

  @Override
  public MakePartSpec create(MakePartSpecUpdateRequest request) {
    if (request == null || !StringUtils.hasText(request.getMaterialCode())) {
      return null;
    }
    MakePartSpec item = new MakePartSpec();
    merge(item, request);
    fillDefaults(item);
    specMapper.insert(item);
    return item;
  }

  @Override
  public MakePartSpec update(Long id, MakePartSpecUpdateRequest request) {
    if (id == null) return null;
    MakePartSpec existing = specMapper.selectById(id);
    if (existing == null) return null;
    merge(existing, request);
    fillDefaults(existing);
    specMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && specMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<MakePartSpec> importItems(MakePartSpecImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<MakePartSpec> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null || !StringUtils.hasText(row.getMaterialCode())) continue;
      MakePartSpec existing = findExisting(row);
      if (existing == null) {
        MakePartSpec item = new MakePartSpec();
        fillFromRow(item, row);
        fillDefaults(item);
        specMapper.insert(item);
        imported.add(item);
      } else {
        fillFromRow(existing, row);
        fillDefaults(existing);
        specMapper.updateById(existing);
        imported.add(existing);
      }
    }
    return imported;
  }

  /** 去重锚点：(material_code, period) */
  private MakePartSpec findExisting(MakePartSpecImportRequest.MakePartSpecImportRow row) {
    String period = StringUtils.hasText(row.getPeriod()) ? row.getPeriod().trim() : defaultPeriod();
    var query = Wrappers.lambdaQuery(MakePartSpec.class)
        .eq(MakePartSpec::getMaterialCode, row.getMaterialCode().trim())
        .eq(MakePartSpec::getPeriod, period)
        .last("LIMIT 1");
    return specMapper.selectOne(query);
  }

  private void fillFromRow(MakePartSpec item, MakePartSpecImportRequest.MakePartSpecImportRow row) {
    item.setMaterialCode(row.getMaterialCode());
    item.setMaterialName(row.getMaterialName());
    item.setDrawingNo(row.getDrawingNo());
    item.setPeriod(row.getPeriod());
    item.setBlankWeight(row.getBlankWeight());
    item.setNetWeight(row.getNetWeight());
    item.setScrapRate(row.getScrapRate());
    item.setRawMaterialCode(row.getRawMaterialCode());
    item.setRawMaterialSpec(row.getRawMaterialSpec());
    item.setRawUnitPrice(row.getRawUnitPrice());
    item.setRecycleCode(row.getRecycleCode());
    item.setRecycleUnitPrice(row.getRecycleUnitPrice());
    item.setRecycleRatio(row.getRecycleRatio());
    item.setProcessFee(row.getProcessFee());
    item.setOutsourceFee(row.getOutsourceFee());
    item.setFormulaId(row.getFormulaId());
    item.setEffectiveFrom(row.getEffectiveFrom());
    item.setEffectiveTo(row.getEffectiveTo());
    item.setRemark(row.getRemark());
  }

  private void merge(MakePartSpec item, MakePartSpecUpdateRequest request) {
    if (request == null) return;
    if (request.getMaterialCode() != null) item.setMaterialCode(request.getMaterialCode());
    if (request.getMaterialName() != null) item.setMaterialName(request.getMaterialName());
    if (request.getDrawingNo() != null) item.setDrawingNo(request.getDrawingNo());
    if (request.getPeriod() != null) item.setPeriod(request.getPeriod());
    if (request.getBlankWeight() != null) item.setBlankWeight(request.getBlankWeight());
    if (request.getNetWeight() != null) item.setNetWeight(request.getNetWeight());
    if (request.getScrapRate() != null) item.setScrapRate(request.getScrapRate());
    if (request.getRawMaterialCode() != null) item.setRawMaterialCode(request.getRawMaterialCode());
    if (request.getRawMaterialSpec() != null) item.setRawMaterialSpec(request.getRawMaterialSpec());
    if (request.getRawUnitPrice() != null) item.setRawUnitPrice(request.getRawUnitPrice());
    if (request.getRecycleCode() != null) item.setRecycleCode(request.getRecycleCode());
    if (request.getRecycleUnitPrice() != null) item.setRecycleUnitPrice(request.getRecycleUnitPrice());
    if (request.getRecycleRatio() != null) item.setRecycleRatio(request.getRecycleRatio());
    if (request.getProcessFee() != null) item.setProcessFee(request.getProcessFee());
    if (request.getOutsourceFee() != null) item.setOutsourceFee(request.getOutsourceFee());
    if (request.getFormulaId() != null) item.setFormulaId(request.getFormulaId());
    if (request.getEffectiveFrom() != null) item.setEffectiveFrom(request.getEffectiveFrom());
    if (request.getEffectiveTo() != null) item.setEffectiveTo(request.getEffectiveTo());
    if (request.getRemark() != null) item.setRemark(request.getRemark());
  }

  private void fillDefaults(MakePartSpec item) {
    if (StringUtils.hasText(item.getMaterialCode())) item.setMaterialCode(item.getMaterialCode().trim());
    if (!StringUtils.hasText(item.getPeriod())) item.setPeriod(defaultPeriod());
  }
}
