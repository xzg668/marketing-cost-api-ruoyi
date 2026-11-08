package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.ManufacturingRawNode;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.MaterialSnapshot;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 将本产品已存原材料行交给既有组树器；组树器仍先查 U9，只有明确无下级才采用。 */
@Service
public class TechnicalDataManufacturingBomSource {
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataVersionContentCodec codec;
  private final MaterialMasterRawMapper materials;
  private final com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingReusedManufacturing drawingReuse;

  public TechnicalDataManufacturingBomSource(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository tasks, TechnicalDataVersionContentCodec codec, MaterialMasterRawMapper materials,
      com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingReusedManufacturing drawingReuse) {
    this.drawingReuse = drawingReuse;
    this.repository = repository; this.tasks = tasks; this.codec = codec; this.materials = materials;
  }

  public List<ManufacturingRawNode> load(ElectronicDrawingWorkContext context) {
    var reused = reused(context);
    var product = repository.findActiveProduct(context.oaFormItemId(), context.accountingMonth()).orElse(null);
    if (product == null) return reused;
    var module = tasks.findModules(product.getId()).stream().filter(row -> "MANUFACTURING".equals(row.getModuleType())).findFirst().orElse(null);
    if (module == null || !Objects.equals(module.getRequiredFlag(), 1)
        || !"MISSING".equals(module.getSourceAvailability())) return reused;
    Long selected = Set.of("FROZEN", "SUBMITTED", "APPROVED").contains(module.getModuleStatus())
        ? module.getCurrentVersionId() : product.getCurrentEditVersionId();
    if (selected == null) return reused;
    var version = repository.findVersion(selected).orElseThrow(() -> invalid("制造件补录版本不存在"));
    if (!Objects.equals(version.getProductId(), product.getId())) throw invalid("制造件补录版本归属不一致");
    var content = codec.manufacturing(version);
    if (content == null) return reused;
    if (!TechnicalDataManufacturingRules.validate(content, product).isEmpty()) throw invalid("制造件补录输入或单位不完整");
    if (!Objects.equals(content.evidence().drawingSourceVersionId(), context.sourceVersionId())) {
      if (drawingReuse.verifiedRevision(context)) return reused;
      throw invalid("图库版本已变化，请核实原材料关系，不能跨来源节点套用");
    }
    var result = new ArrayList<ManufacturingRawNode>();
    for (var row : content.items()) {
      var found =
          materials.selectByLatestBatchAndCodes(
              Set.of(row.rawMaterialNo()), null, context.materialOrgCode());
      if (found.size() != 1 || !Objects.equals(found.getFirst().getUnit(), row.unit()))
        throw invalid("原材料料品或采购单位已变化，请重新核实");
      MaterialMasterRaw raw = found.getFirst();
      var material =
          new MaterialSnapshot(
              raw.getMaterialCode(),
              raw.getMaterialName(),
              raw.getMaterialSpec(),
              raw.getMaterialModel(),
              raw.getDrawingNo(),
              raw.getShapeAttr(),
              raw.getMainCategoryCode(),
              raw.getProductionCategory(),
              raw.getCostElement(),
              raw.getUnit());
      result.add(
          new ManufacturingRawNode(
              selected,
              row.itemKey(),
              Long.valueOf(row.parentSourceNodeId()),
              row.parentMaterialNo(),
              material,
              row.quantityPerParent()));
    }
    var own = result.stream().map(ManufacturingRawNode::parentSourceNodeId).collect(java.util.stream.Collectors.toSet());
    reused.stream().filter(row -> !own.contains(row.parentSourceNodeId())).forEach(result::add);
    return List.copyOf(result);
  }

  private List<ManufacturingRawNode> reused(ElectronicDrawingWorkContext context) {
    var result = new ArrayList<ManufacturingRawNode>();
    for (var reused : drawingReuse.read(context)) {
      var input = reused.input();
      var matches = materials.selectByLatestBatchAndCodes(Set.of(input.rawMaterialNo()), null, context.materialOrgCode());
      if (matches.size() != 1 || !Objects.equals(matches.getFirst().getUnit(), input.unit()))
        throw invalid("复用原材料的料品或采购单位已变化，请重新核实");
      var raw = matches.getFirst();
      var material = new MaterialSnapshot(raw.getMaterialCode(), raw.getMaterialName(), raw.getMaterialSpec(),
          raw.getMaterialModel(), raw.getDrawingNo(), raw.getShapeAttr(), raw.getMainCategoryCode(),
          raw.getProductionCategory(), raw.getCostElement(), raw.getUnit());
      result.add(new ManufacturingRawNode(reused.technicalVersionId(), reused.itemKey(), reused.nodeId(),
          input.parentMaterialNo(), material, input.quantityPerParent()));
    }
    return List.copyOf(result);
  }

  private ElectronicDrawingHybridBomException invalid(String message) {
    return new ElectronicDrawingHybridBomException(
        ElectronicDrawingHybridBomException.MAPPING_INCOMPLETE, message);
  }
}
