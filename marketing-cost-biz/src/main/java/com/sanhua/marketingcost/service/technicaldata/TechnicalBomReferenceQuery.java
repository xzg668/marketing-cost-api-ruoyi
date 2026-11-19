package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Exact selected finished product -> existing standard-branch reader -> parent-relative input. */
@Component
public class TechnicalBomReferenceQuery {
  private final FormalBomReadService formal;
  private final MaterialMasterRawMapper materials;
  private final BomRawHierarchyMapper hierarchy;
  private final BomU9SourceMapper u9;
  private final OaMessageCodec json;
  public TechnicalBomReferenceQuery(FormalBomReadService formal, MaterialMasterRawMapper materials,
      BomRawHierarchyMapper hierarchy, BomU9SourceMapper u9, OaMessageCodec json) {
    this.formal = formal; this.materials = materials; this.hierarchy = hierarchy; this.u9 = u9; this.json = json;
  }
  public record Candidate(String code, String name, String model, String specification) {}
  public record Result(TechnicalBomRows.Product product, List<TechnicalBomRows.Row> rows,
      TechnicalBomContent.Reference evidence, Map<String, TechnicalBomContent.ReferenceLine> sourceLines) {
    public Result { rows = List.copyOf(rows); sourceLines = Map.copyOf(sourceLines); }
  }
  public List<Candidate> search(QuoteBomReadContext context, String keyword) {
    String query = text(keyword);
    if (query == null || query.length() < 2 || query.length() > 100) throw new IllegalArgumentException("请输入2至100字的成品料号或型号");
    // Return candidates for explicit selection; search never treats the first fuzzy match as a BOM.
    return materials.selectOptionsByLatestBatchKeyword(query, null, context.materialOrganizationCode(), 50).stream()
        .filter(row -> contains(row.getMaterialCode(), query) || contains(row.getMaterialModel(), query))
        .map(row -> new Candidate(row.getMaterialCode(), row.getMaterialName(), row.getMaterialModel(), row.getMaterialSpec()))
        .distinct().toList();
  }
  List<Candidate> searchReplacement(QuoteBomReadContext context, String keyword) {
    String query=text(keyword);
    if (query==null || query.length()>100) throw new IllegalArgumentException("请输入新料号或型号");
    var exact=materials.selectByLatestBatchAndCodes(List.of(query), null, context.materialOrganizationCode());
    if (exact.isEmpty()) exact=materials.selectDrawingProductCandidates(null, query, null, context.materialOrganizationCode());
    if (exact.isEmpty()) return query.length()<2 ? List.of() : search(context, query);
    return exact.stream().map(row -> new Candidate(row.getMaterialCode(),row.getMaterialName(),
        row.getMaterialModel(),row.getMaterialSpec())).distinct().limit(50).toList();
  }
  public Result read(QuoteBomReadContext context, String code) {
    code = text(code);
    if (code == null) throw new IllegalArgumentException("请选择明确的成品料号");
    var master = uniqueMaterial(code, context.materialOrganizationCode());
    var result = formal.readReference(code, context.accountingMonth(), "主制造", context.bomEffectiveDate(), context.organization());
    if (result == null) throw new IllegalStateException("成品BOM读取未返回结论");
    if (!result.found()) throw new IllegalArgumentException("该成品没有可参考的适用BOM：" + Objects.toString(result.gapMessage(), "请核实月份、组织和用途"));
    var lines = result.lines();
    if (lines == null || lines.size() < 2 || lines.size() > TechnicalBomRows.MAX_ROWS + 1) throw new IllegalArgumentException("成品BOM为空或超过5000条子件");
    final String selectedCode = code;
    var roots = lines.stream().filter(line -> Objects.equals(line.level(), 0) && Objects.equals(line.materialCode(), selectedCode)).toList();
    if (roots.size() != 1) throw new IllegalArgumentException("参考成品BOM须有唯一根节点");
    var root = roots.getFirst();
    Map<String, QuoteBomSourceLineDto> paths = new LinkedHashMap<>();
    for (var line : lines) {
      if (line.path() == null || line.level() == null || paths.putIfAbsent(path(line.path()), line) != null)
        throw new IllegalArgumentException("参考BOM路径缺失或重复，请核实来源");
      var organization = com.sanhua.marketingcost.enums.MaterialOrganization.fromPriceOrgCode(line.priceOrgCode());
      if (!Objects.equals(organization.getCode(), line.materialOrganizationCode()))
        throw new IllegalArgumentException("参考BOM的价格组织与物料组织不一致");
    }
    Map<String, List<QuoteBomSourceLineDto>> children = new HashMap<>();
    for (var line : lines) {
      if (line == root) continue;
      String parentPath = parentPath(line.path());
      var parent = paths.get(parentPath);
      if (parent == null || parent.level() + 1 != line.level() || !Objects.equals(parent.materialCode(), line.parentCode()))
        throw new IllegalArgumentException("参考BOM父子关系不完整：" + line.path());
      children.computeIfAbsent(parentPath, ignored -> new ArrayList<>()).add(line);
    }
    children.values().forEach(values -> values.sort(Comparator.comparing(QuoteBomSourceLineDto::sortSeq, Comparator.nullsLast(Integer::compareTo))
        .thenComparing(QuoteBomSourceLineDto::path)));
    var output = new ArrayList<TechnicalBomRows.Row>();
    Map<String, TechnicalBomContent.ReferenceLine> evidence = new LinkedHashMap<>();
    append(root, "", children, output, evidence, context, 0);
    if (output.size() != lines.size() - 1) throw new IllegalArgumentException("参考BOM存在不连通节点");
    var product = new TechnicalBomRows.Product(code, master.getMaterialName(), master.getMaterialModel(), master.getMaterialSpec());
    String fingerprint = json.dataFingerprint(List.of(context.organization(), context.bomEffectiveDate(), lines, output, evidence));
    var source = new TechnicalBomContent.Reference(code, master.getMaterialName(), master.getMaterialModel(), master.getMaterialSpec(),
        context.materialOrganizationCode(), context.priceOrgCode(), "主制造", lines.stream().map(QuoteBomSourceLineDto::bomVersion).filter(Objects::nonNull).distinct().sorted().toList(), context.bomEffectiveDate(), fingerprint);
    return new Result(product, output, source, evidence);
  }
  private void append(QuoteBomSourceLineDto parent, String parentHierarchy, Map<String, List<QuoteBomSourceLineDto>> children,
      List<TechnicalBomRows.Row> output, Map<String, TechnicalBomContent.ReferenceLine> evidence, QuoteBomReadContext context, int depth) {
    if (depth >= TechnicalBomRows.MAX_DEPTH) throw new IllegalArgumentException("参考BOM层级超过限制");
    var values = children.getOrDefault(path(parent.path()), List.of());
    for (int index = 0; index < values.size(); index++) {
      var line = values.get(index);
      String key = parentHierarchy.isEmpty() ? String.valueOf(index + 1) : parentHierarchy + "." + (index + 1);
      var master = uniqueMaterial(line.materialCode(), line.materialOrganizationCode());
      var raw = line.sourceRawHierarchyId() == null ? null : hierarchy.selectById(line.sourceRawHierarchyId());
      if (raw == null || !Objects.equals(raw.getMaterialCode(), line.materialCode())
          || !Objects.equals(raw.getBomVersion(), line.bomVersion()))
        throw new IllegalArgumentException("参考BOM原始行已变化：" + line.materialCode());
      var source = line.sourceU9BomId() == null ? null : u9.selectById(line.sourceU9BomId());
      if (line.sourceU9BomId() != null && source == null) throw new IllegalArgumentException("参考BOM原始用量行已不存在：" + line.materialCode());
      BigDecimal original = line.qtyPerParent(), base = null;
      if (source != null) {
        if (!Objects.equals(source.getPriceOrgCode(), raw.getPriceOrgCode())
            || !Objects.equals(source.getParentMaterialNo(), parent.materialCode())
            || !Objects.equals(source.getChildMaterialNo(), line.materialCode())
            || !Objects.equals(source.getBomVersion(), line.bomVersion())
            || !Objects.equals(source.getImportBatchId(), raw.getSourceImportBatchId()))
          throw new IllegalArgumentException("参考BOM原始用量依据归属不一致：" + line.materialCode());
        original = source.getQtyPerParent(); base = source.getParentBaseQty();
      }
      BigDecimal relative = relative(original, base);
      String attribute = attribute(line.shapeAttr());
      BigDecimal net = netWeight(master);
      output.add(new TechnicalBomRows.Row(output.size() + 2, key, line.materialCode(), line.materialName(), line.materialModel(), line.materialSpec(),
          relative, line.unit(), attribute, master.getGlobalSeg4Material(), net, net == null ? "" : "g"));
      evidence.put(key, new TechnicalBomContent.ReferenceLine(line.sourceRawHierarchyId(), line.sourceU9BomId(), line.path(), line.bomVersion(), original, base, relative,
          line.priceOrgCode(), line.materialOrganizationCode()));
      append(line, key, children, output, evidence, context, depth + 1);
    }
  }
  record Replacement(TechnicalBomRows.Row row, List<TechnicalBomRows.Row> children,
      Map<String, TechnicalBomContent.ReferenceLine> lines) {}

  Replacement replacement(QuoteBomReadContext context, TechnicalBomRows.Row previous, String code) {
    if (text(code) == null) throw new IllegalArgumentException("请选择明确的替换料号");
    var master = uniqueMaterial(code.trim(), context.materialOrganizationCode());
    String shape = attribute(master.getShapeAttr());
    if (!List.of("采购件", "制造件", "委外加工件").contains(Objects.toString(shape, "")))
      throw new IllegalArgumentException("替换料号没有明确的采购、制造或委外属性：" + master.getMaterialCode());
    BigDecimal net = netWeight(master);
    var row = new TechnicalBomRows.Row(previous.lineNo(), previous.hierarchy(), master.getMaterialCode(),
        master.getMaterialName(), master.getMaterialModel(), master.getMaterialSpec(), previous.quantity(),
        master.getUnit(), shape, master.getGlobalSeg4Material(), net, net == null ? "" : "g");
    if ("采购件".equals(shape)) return new Replacement(row, List.of(), Map.of());
    var branch = read(context, master.getMaterialCode());
    return new Replacement(row, branch.rows(), branch.sourceLines());
  }
  private static String attribute(String shape) {
    return switch (Objects.toString(shape, "")) {
      case "委外件", "委外", "委外加工", "OUTSOURCE" -> "委外加工件";
      case "制造", "自制件", "MANUFACTURE" -> "制造件";
      case "采购", "外购件", "PURCHASE" -> "采购件";
      case "虚拟", "VIRTUAL" -> "虚拟件";
      default -> shape;
    };
  }
  private static BigDecimal netWeight(MaterialMasterRaw master) {
    String value = text(master.getGlobalSeg3TheoreticalNetWeight());
    if (value == null) return null;
    try { return new BigDecimal(value); }
    catch (NumberFormatException ignored) { return null; }
  }

  static BigDecimal relative(BigDecimal child, BigDecimal base) {
    if (child == null || child.signum() < 0 || base != null && base.signum() <= 0) throw new IllegalArgumentException("参考BOM用量不能为负数，母件底数须大于零");
    // With an ODS line use its original quantity/base. Without one, DWD quantity already means per direct parent.
    return base == null ? child : child.divide(base, 12, RoundingMode.HALF_UP).stripTrailingZeros();
  }
  private MaterialMasterRaw uniqueMaterial(String code, String organization) {
    var found = materials.selectByLatestBatchAndCodes(List.of(code), null, organization);
    if (found.size() != 1) throw new IllegalArgumentException("料号在当前组织没有唯一有效档案：" + code);
    return found.getFirst();
  }
  private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private static String path(String value) { return value == null ? null : value.replaceAll("/+$", ""); }
  private static String parentPath(String value) { value = path(value); int last = value.lastIndexOf('/'); return last < 0 ? null : value.substring(0, last); }
  private static boolean contains(String value, String query) { return value != null && value.toUpperCase(java.util.Locale.ROOT).contains(query.toUpperCase(java.util.Locale.ROOT)); }
}
