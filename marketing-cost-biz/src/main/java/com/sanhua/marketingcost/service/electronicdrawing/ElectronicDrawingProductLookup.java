package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationProperties;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 产品图号优先按料号查；无料号时先型号加规格，无记录再按型号查。 */
@Component
public class ElectronicDrawingProductLookup {
  private final OaFormItemMapper items;
  private final MaterialMasterRawMapper materials;
  private final DrawingVerificationProperties properties;

  public ElectronicDrawingProductLookup(
      OaFormItemMapper items, MaterialMasterRawMapper materials,
      DrawingVerificationProperties properties) {
    this.items = items;
    this.materials = materials;
    this.properties = properties;
  }

  /** 料品候选保留各自料号，供后续 BOM 顶层料品核实使用。 */
  public List<Option> options(ElectronicDrawingWorkContext context) {
    return materialOptions(context, requireItem(context));
  }

  private OaFormItem requireItem(ElectronicDrawingWorkContext context) {
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
    return item;
  }

  private List<Option> materialOptions(ElectronicDrawingWorkContext context, OaFormItem item) {
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

  /** 取图只要求图号唯一；共用图号不表示已经选定其中一个正式料号。 */
  public List<Option> drawingOptions(ElectronicDrawingWorkContext context) {
    OaFormItem item = requireItem(context);
    List<Option> candidates;
    if (properties.isUseMaterialMaster()) {
      candidates = materialOptions(context, item);
    } else {
      String drawing = text(item.getCustomerDrawing());
      candidates = drawing == null ? List.of() : List.of(
          new Option(drawing, null, text(item.getProductName()), text(item.getSunlModel()),
              "QUOTE_CUSTOMER_DRAWING"));
    }
    Map<String, Option> byDrawing = new LinkedHashMap<>();
    for (Option candidate : candidates) {
      Option option = new Option(verificationDrawingNo(candidate.drawingNo()), candidate.materialNo(),
          candidate.name(), candidate.model(), candidate.source());
      byDrawing.merge(
          option.drawingNo().toUpperCase(Locale.ROOT),
          option,
          (first, duplicate) ->
              new Option(first.drawingNo(), null, null, null, first.source()));
    }
    return List.copyOf(byDrawing.values());
  }

  public String requireDrawing(ElectronicDrawingWorkContext context, String requestedDrawing) {
    List<Option> candidates = drawingOptions(context);
    String requested = verificationDrawingNo(requestedDrawing);
    List<Option> selected =
        requested == null
            ? candidates
            : candidates.stream().filter(option -> same(option.drawingNo(), requested)).toList();
    if (selected.size() == 1) return selected.getFirst().drawingNo();
    String message = "请从本产品实际查到的候选中选择唯一图号";
    if (candidates.isEmpty()) {
      message = properties.isUseMaterialMaster()
          ? "料品档案未查到对应料号或型号、规格的有效图号，请核实报价来源"
          : "联调直取模式请在报价产品行填写客户图号";
    }
    throw new ElectronicDrawingSourceImportException(
        ElectronicDrawingSourceImportException.DRAWING_MISMATCH, message);
  }

  /** 产品图号统一为图库明细图号；不用于转换明细行的 code。 */
  public static String verificationDrawingNo(String drawing) {
    String number = text(drawing);
    if (number == null) return null;
    return number.toUpperCase(Locale.ROOT).endsWith("-MX") ? number : number + "-MX";
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
