package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.RawMaterial;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 将技术版本的单件制造输入绑定到真实图库节点路径，供既有结算和制造件计算共同读取。 */
@Service
public class TechnicalManufacturingInputs {
  private final TechnicalDataCostingSources sources;
  private final TechnicalDataVersionContentCodec codec;
  private final QuoteBomSupplementDetailMapper details;
  public TechnicalManufacturingInputs(TechnicalDataCostingSources sources,
      TechnicalDataVersionContentCodec codec, QuoteBomSupplementDetailMapper details) {
    this.sources = sources;
    this.codec = codec;
    this.details = details;
  }

  public Map<String, Input> byParentPath(Long itemId, String month) {
    var source = sources.preparationSources(itemId, month).get("MANUFACTURING");
    if (source == null) return Map.of();
    var content = codec.manufacturing(source.version());
    var issues = TechnicalDataManufacturingRules.validate(content, source.product());
    if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
    var rows =
        details.selectList(
            Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
                .eq(QuoteBomSupplementDetail::getSupplementVersionId, content.evidence().drawingSourceVersionId()));
    Map<String, Input> result = new LinkedHashMap<>();
    for (var material : content.items()) {
      var parents =
          rows.stream()
              .filter(
                  row ->
                      Objects.equals(
                              row.getSourceElectronicNodeId(), Long.valueOf(material.parentSourceNodeId()))
                          && Objects.equals(row.getMaterialCode(), material.parentMaterialNo()))
              .toList();
      if (parents.size() != 1)
        throw new IllegalArgumentException("制造件补录找不到唯一图库节点：" + material.parentSourceNodeId());
      var parent = parents.getFirst();
      requireRawRelation(rows, parent, material);
      if (result.put(
              parent.getPath(), new Input(source.version().getId(), parent.getPath(), material))
          != null) {
        throw new IllegalArgumentException("制造件补录节点重复");
      }
    }
    return Map.copyOf(result);
  }

  private void requireRawRelation(
      java.util.List<QuoteBomSupplementDetail> rows,
      QuoteBomSupplementDetail parent,
      RawMaterial material) {
    if (parent.getPath() == null || !parent.getPath().endsWith("/") || parent.getLevel() == null) {
      throw new IllegalArgumentException("制造件图库节点路径不完整，请重新检查 BOM");
    }
    var children =
        rows.stream()
            .filter(
                row ->
                    Objects.equals(row.getParentCode(), parent.getMaterialCode())
                        && row.getPath() != null
                        && row.getPath().startsWith(parent.getPath())
                        && Objects.equals(row.getLevel(), parent.getLevel() + 1))
            .toList();
    // 一个制造节点只接受一条原材料关系；不能用任意一条相同料号掩盖重复或变更的结构。
    if (children.size() != 1
        || !Objects.equals(children.getFirst().getMaterialCode(), material.rawMaterialNo())) {
      throw new IllegalArgumentException("制造件补录原料与当前图库 BOM 不一致，须有唯一原材料节点");
    }
    var raw = children.getFirst();
    if (!Objects.equals(raw.getUnit(), material.unit())
        || raw.getQtyPerParent() == null
        || raw.getQtyPerParent().compareTo(material.quantityPerParent()) != 0) {
      throw new IllegalArgumentException("制造件原料单位或单件用量与已确认补录不一致，请核实受影响的制造件");
    }
    if (parent.getQtyPerTop() == null
        || raw.getQtyPerTop() == null
        || raw.getQtyPerTop().compareTo(parent.getQtyPerTop().multiply(raw.getQtyPerParent()))
            != 0) {
      throw new IllegalArgumentException("制造件原料总用量与父件数量不一致，请重新检查 BOM");
    }
  }

  public Input forParent(BomCostingRow parent) {
    if (parent == null || parent.getOaFormItemId() == null) return null;
    var input =
        byParentPath(parent.getOaFormItemId(), parent.getPeriodMonth()).get(parent.getPath());
    if (input != null
        && !Objects.equals(input.material().parentMaterialNo(), parent.getMaterialCode())) {
      throw new IllegalArgumentException("制造件计价对象与技术节点不一致");
    }
    return input;
  }

  public com.sanhua.marketingcost.service.settlement.BomByproductSettlementReadResult byproducts(
      Long itemId,
      String month,
      String businessUnit,
      java.util.List<com.sanhua.marketingcost.service.settlement.BomSettlementNode> nodes,
      com.sanhua.marketingcost.service.settlement.BomByproductSettlementReadResult publicResult) {
    var values = new java.util.ArrayList<>(publicResult.byproducts());
    for (var input : byParentPath(itemId, month).values()) {
      var material = input.material();
      if (nodes.stream().noneMatch(node -> input.parentPath().equals(node.path()))) continue;
      // 有实际 U9 副产品仍沿用公共来源。
      if (publicResult.byproducts().stream()
          .anyMatch(row -> material.parentMaterialNo().equals(row.parentMaterialCode()))) continue;
      var evidence = material.evidence();
      if ("NO_SCRAP_CONFIRMED".equals(evidence.scrapStatus())) continue;
      if (!"MATCHED".equals(evidence.scrapStatus()) || evidence.scrapMappings().size() != 1) {
        throw new IllegalArgumentException("制造件原料废料关系尚未唯一确认");
      }
      var scrap = evidence.scrapMappings().getFirst();
      var weights = TechnicalDataManufacturingRules.calculationInput(material);
      var grams = weights.grossWeightG().subtract(weights.netWeightG());
      if (grams.signum() < 0) throw new IllegalArgumentException("制造件毛重不能小于净重");
      var quantity =
          switch (scrap.unit()) {
            case "kg", "KG", "千克", "公斤" -> grams.movePointLeft(3);
            case "g", "G", "克" -> grams;
            default -> throw new IllegalArgumentException("制造件废料采购单位不能换算重量：" + scrap.unit());
          };
      values.add(
          new com.sanhua.marketingcost.service.settlement.BomSettlementByproduct(
              null,
              material.parentMaterialNo(),
              scrap.materialNo(),
              scrap.name(),
              null,
              quantity,
              scrap.unit(),
              "主制造",
              "TECH:" + input.versionId(),
              java.time.YearMonth.parse(month).atDay(1),
              null,
              businessUnit,
              input.parentPath()));
    }
    return new com.sanhua.marketingcost.service.settlement.BomByproductSettlementReadResult(
        java.util.List.copyOf(values), publicResult.scrapRefs(), publicResult.warnings());
  }

  public record Input(Long versionId, String parentPath, RawMaterial material) {}
}
