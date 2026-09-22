package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 图号只能来自 OA 原值或当前物料组织的真实料品档案，型号本身不是图号。 */
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
    String drawing = text(item.getCustomerDrawing());
    if (drawing != null) return List.of(new Option(drawing, text(item.getMaterialNo()),
        text(item.getProductName()), text(item.getSunlModel()), "OA"));
    String code = text(item.getMaterialNo());
    List<MaterialMasterRaw> rows;
    if (code != null) {
      rows = materials.selectByLatestBatchAndCodes(Set.of(code), null, context.materialOrgCode());
    } else {
      String model = text(item.getSunlModel());
      if (model == null) return List.of();
      rows = materials.selectByDrawingIdentities(Set.of(model.toUpperCase(Locale.ROOT)),
          null, context.materialOrgCode(), 1000).stream()
          .filter(row -> same(model, row.getMaterialModel())).toList();
    }
    return rows.stream().filter(row -> text(row.getDrawingNo()) != null)
        .map(row -> new Option(text(row.getDrawingNo()), text(row.getMaterialCode()),
            text(row.getMaterialName()), text(row.getMaterialModel()), "MATERIAL_MASTER"))
        .distinct().sorted(Comparator.comparing(Option::drawingNo)
            .thenComparing(Option::materialNo, Comparator.nullsLast(String::compareTo))).toList();
  }

  public String requireDrawing(ElectronicDrawingWorkContext context, String requestedDrawing) {
    List<String> drawings = options(context).stream().map(Option::drawingNo).distinct().toList();
    if (text(requestedDrawing) == null && drawings.size() == 1) return drawings.getFirst();
    return drawings.stream().filter(value -> same(value, requestedDrawing)).findFirst()
        .orElseThrow(() -> new ElectronicDrawingSourceImportException(ElectronicDrawingSourceImportException.DRAWING_MISMATCH, drawings.isEmpty()
            ? "OA 未提供图号，料品档案也未查到本产品图号，请先核实来源资料"
            : "请从本产品实际查到的图号中选择，不能自行填写或使用其他产品图号"));
  }

  private static boolean same(String left, String right) {
    return text(left) != null && text(right) != null && left.trim().equalsIgnoreCase(right.trim());
  }

  private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }

  public record Option(String drawingNo, String materialNo, String name, String model, String source) {}
}
