package com.sanhua.marketingcost.service.platematerial;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.PlateMaterialMapping;
import com.sanhua.marketingcost.mapper.PlateMaterialMappingMapper;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlateMaterialMappingService {
  public record MappingPage(List<PlateMaterialMapping> records, long total, int page, int size) {}

  private final PlateMaterialMappingMapper mapper;

  public PlateMaterialMappingService(PlateMaterialMappingMapper mapper) {
    this.mapper = mapper;
  }

  public MappingPage list(String unit, String model, String material, int page, int size) {
    requireUnit(unit);
    if (page < 1 || size < 1 || size > 200) throw new IllegalArgumentException("页码须大于 0，每页须为 1～200 条");
    String normalized = PlateMaterialModel.normalize(model);
    var query = new LambdaQueryWrapper<PlateMaterialMapping>()
        .eq(PlateMaterialMapping::getBusinessUnitType, unit);
    // Match a complete model; %, _ and case differences are literal business identity.
    if (normalized != null) query.eq(PlateMaterialMapping::getNormalizedModel, normalized);
    if (material != null && !material.isEmpty()) {
      query.eq(PlateMaterialMapping::getPlateMaterial, PlateMaterial.parse(material).value());
    }
    query.orderByAsc(PlateMaterialMapping::getNormalizedModel);
    var result = mapper.selectPage(new Page<>(page, size), query);
    return new MappingPage(result.getRecords(), result.getTotal(), page, size);
  }

  @Transactional
  public PlateMaterialMapping create(String unit, String model, String material, long actor) {
    requireUnit(unit);
    requireActor(actor);
    var row = new PlateMaterialMapping();
    row.setBusinessUnitType(unit);
    row.setNormalizedModel(PlateMaterialModel.require(model));
    row.setPlateMaterial(PlateMaterial.parse(material).value());
    row.setRowVersion(0);
    row.setSourceType("MANUAL");
    row.setCreatedBy(actor);
    row.setUpdatedBy(actor);
    mapper.insert(row);
    return mapper.selectById(row.getId());
  }

  /** Called only after a technical version becomes effective; never overwrites public configuration. */
  @Transactional
  public PlateMaterialMapping registerConfirmedSelection(String unit, String model, String material, long actor) {
    requireUnit(unit);
    requireActor(actor);
    var row = new PlateMaterialMapping();
    row.setBusinessUnitType(unit);
    row.setNormalizedModel(PlateMaterialModel.require(model));
    row.setPlateMaterial(PlateMaterial.parse(material).value());
    row.setCreatedBy(actor);
    row.setUpdatedBy(actor);
    // The unique key arbitrates concurrent confirmations without modifying an existing row.
    mapper.insertConfirmedIfAbsent(row);
    var actual = mapper.findModels(unit, List.of(row.getNormalizedModel()), true).getFirst();
    if (!row.getPlateMaterial().equals(actual.getPlateMaterial())) {
      throw new OptimisticLockingFailureException(
          "型号 " + row.getNormalizedModel() + " 已配置板片材质 " + actual.getPlateMaterial()
              + "，与本次确认的 " + row.getPlateMaterial() + " 不一致，请核实后重新确认");
    }
    return actual;
  }

  @Transactional
  public PlateMaterialMapping update(String unit, long id, String model, String material,
      Integer expectedVersion, long actor) {
    requireUnit(unit);
    requireActor(actor);
    if (expectedVersion == null || expectedVersion < 0) throw new IllegalArgumentException("预期行版本必填且不能为负数");
    String normalized = PlateMaterialModel.require(model);
    String value = PlateMaterial.parse(material).value();
    // Even administrators stay within their selected business unit.
    var rows = mapper.selectList(new LambdaQueryWrapper<PlateMaterialMapping>()
        .eq(PlateMaterialMapping::getBusinessUnitType, unit).eq(PlateMaterialMapping::getId, id)
        .last("FOR UPDATE"));
    if (rows.isEmpty()) throw new IllegalArgumentException("当前业务单元内未找到该型号配置");
    var current = rows.getFirst();
    if (!expectedVersion.equals(current.getRowVersion())) throw changed();
    if (normalized.equals(current.getNormalizedModel()) && value.equals(current.getPlateMaterial())) return current;
    var replacement = new PlateMaterialMapping();
    replacement.setId(id);
    replacement.setBusinessUnitType(unit);
    replacement.setNormalizedModel(normalized);
    replacement.setPlateMaterial(value);
    replacement.setSourceType("MANUAL");
    replacement.setUpdatedBy(actor);
    if (mapper.updateWithVersion(replacement, expectedVersion) != 1) throw changed();
    return mapper.selectById(id);
  }

  static void requireUnit(String unit) {
    if (!"COMMERCIAL".equals(unit) && !"HOUSEHOLD".equals(unit)) {
      throw new IllegalArgumentException("当前业务单元缺失或无效，请重新登录");
    }
  }

  static void requireActor(long actor) {
    if (actor <= 0) throw new IllegalArgumentException("请登录后维护型号配置");
  }

  static OptimisticLockingFailureException changed() {
    return new OptimisticLockingFailureException("型号配置已变化，请刷新或重新预览后确认");
  }
}
