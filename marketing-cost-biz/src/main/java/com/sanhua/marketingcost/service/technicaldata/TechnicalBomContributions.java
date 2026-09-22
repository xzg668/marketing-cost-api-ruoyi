package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 将批准的包装、焊料数量接入现有组树；不写 U9 原始资料，也不在这里计算价格。 */
@Service
public class TechnicalBomContributions {
  private final EffectiveTechnicalDataQueryService inputs;
  private final MaterialMasterRawMapper materials;
  public TechnicalBomContributions(EffectiveTechnicalDataQueryService inputs, MaterialMasterRawMapper materials) {
    this.inputs = inputs;
    this.materials = materials;
  }

  public List<BomRawHierarchy> merge(Long itemId, String month, List<BomRawHierarchy> original) {
    var materialItems = inputs.preparationMaterials(itemId, month);
    if (materialItems.isEmpty()) return original;
    var roots = original.stream().filter(row -> Integer.valueOf(0).equals(row.getLevel())).toList();
    if (roots.size() != 1) throw new IllegalArgumentException("补录材料缺少唯一的本产品 BOM 根节点");
    var root = roots.getFirst();
    var codes = new java.util.LinkedHashSet<String>();
    original.forEach(row -> codes.add(row.getMaterialCode()));
    materialItems.forEach(row -> codes.add(row.materialNo()));
    var organization = MaterialOrganization.fromPriceOrgCode(root.getPriceOrgCode()).toQuoteDataOrganization();
    Map<String, MaterialMasterRaw> masters = materials.selectByLatestBatchAndCodes(
        List.copyOf(codes), null, organization.materialOrganizationCode()).stream()
        .collect(Collectors.toMap(MaterialMasterRaw::getMaterialCode, Function.identity(), (first, second) -> {
          throw new IllegalArgumentException("当前组织存在重复料品档案：" + first.getMaterialCode());
        }));
    boolean replaceSolder = materialItems.stream().anyMatch(row -> "SOLDER".equals(row.moduleType()));
    // 无 U9 原始 BOM 时，技术确认的焊料清单代表本次全部焊料；不能再叠加图库原用量。
    var removedPaths = original.stream().filter(row -> replaceSolder && isSolder(masters.get(row.getMaterialCode())))
        .map(BomRawHierarchy::getPath).toList();
    List<BomRawHierarchy> result = new ArrayList<>(original.stream().filter(row ->
        removedPaths.stream().noneMatch(path -> row.getPath().startsWith(path))).toList());
    int sequence = original.stream().map(BomRawHierarchy::getSortSeq).filter(Objects::nonNull)
        .max(Integer::compareTo).orElse(0) + 1;
    for (var line : materialItems) {
      var master = masters.get(line.materialNo());
      BigDecimal quantity = line.quantityPerProduct();
      if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("补录材料用量必须大于零：" + line.materialNo());
      if ("SOLDER".equals(line.moduleType())) {
        if (!isSolder(master)) throw new IllegalArgumentException("焊料档案或适用分类已变化：" + line.materialNo());
        quantity = TechnicalDataSolderRules.toKg(quantity, line.unit())
            .divide(TechnicalDataSolderRules.toKg(BigDecimal.ONE, master.getUnit()), 16, RoundingMode.UNNECESSARY)
            .stripTrailingZeros();
      } else if (master != null && !unit(line.unit()).equals(unit(master.getUnit()))) {
        throw new IllegalArgumentException("包装用量单位与采购单位不一致：" + line.materialNo());
      }
      var row = new BomRawHierarchy();
      row.setTopProductCode(root.getTopProductCode()); row.setParentCode(root.getMaterialCode());
      row.setMaterialCode(line.materialNo()); row.setMaterialName(line.name());
      row.setPriceOrgCode(root.getPriceOrgCode()); row.setBusinessUnitType(root.getBusinessUnitType());
      row.setLevel(1); row.setSortSeq(sequence++); row.setQtyPerParent(quantity); row.setQtyPerTop(quantity);
      row.setSourceLineKey("TECH:" + line.itemKey());
      row.setPath(root.getPath() + line.materialNo() + "@TECH_" + line.itemKey().replace(':','_') + "/");
      row.setShapeAttr("采购件"); row.setSourceCategory("采购件"); row.setIsLeaf(1); row.setChildType("标准");
      row.setSourceType("TECH_" + line.moduleType()); row.setBuildBatchId("TECH:" + line.versionId());
      row.setBomVersion(line.versionId().toString()); row.setBomPurpose(root.getBomPurpose());
      row.setCostElementCode("PACKAGE".equals(line.moduleType()) ? "主要材料-包装材料" : "主要材料-焊料");
      result.add(row);
    }
    return List.copyOf(result);
  }

  private boolean isSolder(MaterialMasterRaw material) {
    return material != null && TechnicalDataSolderRules.eligible(material.getMainCategoryCode());
  }
  private String unit(String value) {
    String normalized = Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    return switch(normalized) {
      case "kg", "千克", "公斤" -> "kg";
      case "g", "克" -> "g";
      case "pc", "pcs", "件", "只", "个" -> "piece";
      default -> normalized;
    };
  }
}
