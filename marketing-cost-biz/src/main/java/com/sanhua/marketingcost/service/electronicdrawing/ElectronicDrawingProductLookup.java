package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 产品图号优先按料号查；无料号时先型号加规格，无记录再按型号查。 */
@Component
public class ElectronicDrawingProductLookup {
  private final OaFormItemMapper items;
  private final MaterialMasterRawMapper materials;

  public ElectronicDrawingProductLookup(OaFormItemMapper items, MaterialMasterRawMapper materials) {
    this.items = items;
    this.materials = materials;
  }

  public List<Option> options(ElectronicDrawingWorkContext context) {
    OaFormItem item = items.selectById(context.oaFormItemId());
    if (item == null || !Objects.equals(item.getOaFormId(), context.oaFormId())) {
      throw new IllegalArgumentException("电子图库对应的报价产品不存在");
    }
    if (text(context.materialOrgCode()) == null
        || text(context.businessUnitType()) == null
        || (text(item.getBusinessUnitType()) != null
            && !same(item.getBusinessUnitType(), context.businessUnitType()))) {
      throw new IllegalArgumentException("电子图库报价业务单元或物料组织不一致");
    }
    String code = text(item.getMaterialNo());
    String model = text(item.getSunlModel());
    String specification = text(item.getSpec());
    List<MaterialMasterRaw> rows;
    if (code != null) {
      rows = materials.selectDrawingProductCandidates(code, null, null, context.materialOrgCode());
    } else if (model != null) {
      rows =
          specification == null
              ? List.of()
              : materials.selectDrawingProductCandidates(
                  null, model, specification, context.materialOrgCode());
      if (rows.isEmpty()) {
        rows =
            materials.selectDrawingProductCandidates(null, model, null, context.materialOrgCode());
      }
    } else {
      return List.of();
    }
    return rows.stream()
        .filter(row -> text(row.getDrawingNo()) != null)
        .map(
            row ->
                new Option(
                    text(row.getDrawingNo()),
                    text(row.getMaterialCode()),
                    text(row.getMaterialName()),
                    text(row.getMaterialModel()),
                    "MATERIAL_MASTER"))
        .sorted(
            Comparator.comparing(Option::drawingNo)
                .thenComparing(Option::materialNo, Comparator.nullsLast(String::compareTo)))
        .toList();
  }

  public String requireDrawing(ElectronicDrawingWorkContext context, String requestedDrawing) {
    List<Option> candidates = options(context);
    String requested = text(requestedDrawing);
    List<Option> selected =
        requested == null
            ? candidates
            : candidates.stream().filter(option -> same(option.drawingNo(), requested)).toList();
    if (selected.size() == 1) return selected.getFirst().drawingNo();
    throw new ElectronicDrawingSourceImportException(
        ElectronicDrawingSourceImportException.DRAWING_MISMATCH,
        candidates.isEmpty()
            ? "料品档案未查到对应料号或型号、规格的有效图号，请核实报价来源"
            : "请从本产品实际查到的候选中确定唯一料品；多个料品共用图号时需先核实料号或型号、规格");
  }

  private static boolean same(String left, String right) {
    return text(left) != null && text(right) != null && left.trim().equalsIgnoreCase(right.trim());
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record Option(
      String drawingNo, String materialNo, String name, String model, String source) {}
}
