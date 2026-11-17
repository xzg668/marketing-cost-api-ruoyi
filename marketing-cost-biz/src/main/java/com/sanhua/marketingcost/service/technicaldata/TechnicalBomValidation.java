package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Server-side identity and quantity validation shared by preview, save and submission. */
@Component
public class TechnicalBomValidation {
  private final MaterialMasterRawMapper materials;
  public TechnicalBomValidation(MaterialMasterRawMapper materials) { this.materials = materials; }
  public record Result(List<TechnicalBomContent.Node> nodes, List<TechnicalBomRows.Issue> issues) {
    public Result { nodes = List.copyOf(nodes); issues = List.copyOf(issues); }
  }
  public Result validate(QuoteBomReadContext context, TechnicalBomRows.Product target, TechnicalBomRows.Product input,
      List<TechnicalBomRows.Row> rows, Map<String, TechnicalBomContent.ReferenceLine> reference) {
    var issues = new ArrayList<>(TechnicalBomRows.validate(input, rows, !reference.isEmpty()));
    if (input != null && target != null) {
      identity(issues, "产品料号", target.code(), input.code());
      identity(issues, "产品型号", target.model(), input.model());
      identity(issues, "产品名称", target.name(), input.name());
      identity(issues, "产品规格", target.specification(), input.specification());
      if (text(target.code()) == null && text(input.code()) != null)
        issues.add(new TechnicalBomRows.Issue(null, "产品料号", "本次OA产品尚无料号，请留空；不能填参考成品料号"));
    }
    if (!issues.isEmpty()) return new Result(List.of(), issues);
    var normalized = new ArrayList<TechnicalBomRows.Row>();
    var resolved = new LinkedHashMap<String, MaterialMasterRaw>();
    var parents = new HashSet<String>(); rows.forEach(row -> parents.add(TechnicalBomRows.parent(row.hierarchy())));
    for (var row : rows) {
      try {
        var source = reference.get(row.hierarchy());
        String materialOrganization = source != null && source.materialOrgCode() != null
            ? source.materialOrgCode() : context.materialOrganizationCode();
        var candidates = text(row.materialCode()) != null
            ? materials.selectByLatestBatchAndCodes(List.of(row.materialCode().trim()), null, materialOrganization)
            : materials.selectDrawingProductCandidates(null, text(row.model()), text(row.specification()), materialOrganization);
        if (candidates.size() != 1) throw new IllegalArgumentException("子件未匹配到唯一有效物料，请核实料号／型号／规格");
        var master = candidates.getFirst();
        if (text(master.getMainCategoryCode()) == null) throw new IllegalArgumentException("物料缺少分类，请先核实料品档案");
        String sourceUnit = unit(row.unit()), targetUnit = unit(master.getUnit());
        if (parents.contains(row.hierarchy()) && !Objects.equals(sourceUnit, targetUnit))
          throw new IllegalArgumentException("带下级的母件用量单位须与档案单位一致：" + master.getUnit());
        var quantity = convert(row.quantity(), sourceUnit, targetUnit);
        resolved.put(row.hierarchy(), master);
        normalized.add(new TechnicalBomRows.Row(row.lineNo(), row.hierarchy(), master.getMaterialCode(), row.name(), row.model(),
            row.specification(), quantity, master.getUnit(), row.attribute(), row.material(), row.netWeight(), row.netWeightUnit()));
      } catch (IllegalArgumentException error) { issues.add(new TechnicalBomRows.Issue(row.lineNo(), "子件", error.getMessage())); }
    }
    if (!issues.isEmpty()) return new Result(List.of(), issues);
    var cumulative = TechnicalBomRows.cumulativeQuantities(normalized);
    var nodes = new ArrayList<TechnicalBomContent.Node>();
    for (int index = 0; index < rows.size(); index++) {
      var row = rows.get(index); var master = resolved.get(row.hierarchy());
      if (Set.of("制造件", "委外加工件").contains(row.attribute()) && !parents.contains(row.hierarchy())
          && cumulative.get(row.hierarchy()).signum() > 0)
        issues.add(new TechnicalBomRows.Issue(row.lineNo(), "层级编号", row.attribute() + "缺少下级材料，请在本BOM中补全实际材料关系"));
      nodes.add(new TechnicalBomContent.Node(row, master.getMaterialCode(), master.getMainCategoryCode(), master.getMainCategoryName(),
          master.getCostElement(), master.getUnit(), normalized.get(index).quantity(), cumulative.get(row.hierarchy()), reference.get(row.hierarchy())));
    }
    return new Result(nodes, issues);
  }
  private static void identity(List<TechnicalBomRows.Issue> issues, String field, String expected, String actual) {
    if (text(expected) != null && text(actual) != null && !expected.trim().equalsIgnoreCase(actual.trim()))
      issues.add(new TechnicalBomRows.Issue(null, field, field + "与当前OA产品不一致：应为" + expected));
  }
  static BigDecimal convert(BigDecimal value, String from, String to) {
    if (from == null || to == null) throw new IllegalArgumentException("用量单位或物料档案单位不明确");
    if (from.equals(to)) return value;
    if (from.equals("g") && to.equals("kg") || from.equals("mm") && to.equals("m")) return value.movePointLeft(3);
    if (from.equals("kg") && to.equals("g") || from.equals("m") && to.equals("mm")) return value.movePointRight(3);
    throw new IllegalArgumentException("用量单位" + from + "不能直接换算为物料单位" + to + "，请核实后按明确单位填写");
  }
  static String unit(String value) {
    if (text(value) == null) return null;
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "公斤", "千克", "kg" -> "kg";
      case "克", "g" -> "g";
      case "米", "m" -> "m";
      case "毫米", "mm" -> "mm";
      case "只", "件", "个", "pcs", "pc", "ea" -> "pcs";
      default -> value.trim().toLowerCase(Locale.ROOT);
    };
  }
  private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
