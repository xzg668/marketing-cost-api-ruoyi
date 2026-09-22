package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.Nature;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.SubBomQuery;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.SubBomResult;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.U9Node;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 按精确来源节点检查原材料关系；与混合组树使用同一 U9 查询和物料形态规则。 */
@Service
public class TechnicalDataManufacturingSourceQuery {
  public enum State { U9_READY, DRAWING_READY, MISSING_RAW, WAIT_FINANCE, ERROR }

  public record Node(Long sourceNodeId, String sourceSequence, String parentSourceSequence,
      String name, String drawingNo, String specification, BigDecimal quantityPerParent,
      BigDecimal sourceNetWeight, String sourceNetWeightUnit, String parentMaterialNo,
      State state, String message, List<U9Node> existingChildren) {}

  public record Assessment(Long sourceVersionId, String accountingMonth, String fingerprint,
      List<Node> nodes, String unavailableReason) {
    public boolean hasMissing() { return nodes.stream().anyMatch(node -> node.state() == State.MISSING_RAW); }
    public boolean hasUnresolved() {
      return unavailableReason != null || nodes.stream().anyMatch(node -> node.state() == State.WAIT_FINANCE || node.state() == State.ERROR);
    }
  }

  private final ElectronicDrawingSourceNodeRepository sources;
  private final MaterialMasterRawMapper materials;
  private final ElectronicDrawingU9SubBomPort u9;
  private final OaMessageCodec codec;
  private final QuoteBomSupplementVersionMapper versions;

  public TechnicalDataManufacturingSourceQuery(ElectronicDrawingSourceNodeRepository sources,
      MaterialMasterRawMapper materials, ElectronicDrawingU9SubBomPort u9, OaMessageCodec codec,
      QuoteBomSupplementVersionMapper versions) {
    this.sources = sources; this.materials = materials; this.u9 = u9; this.codec = codec; this.versions = versions;
  }

  public Assessment inspect(ElectronicDrawingWorkContext context) {
    if (context.sourceVersionId() == null) return assessment(context, List.of(), "请先取得本产品的电子图库明细");
    var version = versions.selectById(context.sourceVersionId());
    if (version == null || !Objects.equals(version.getOaFormItemId(), context.oaFormItemId())
        || !Objects.equals(version.getPeriodMonth(), context.accountingMonth())
        || !Objects.equals(version.getMaterialOrgCode(), context.materialOrgCode())
        || !Objects.equals(version.getActiveFlag(), 1)
        || !"ELECTRONIC_DRAWING_EXCEL".equals(version.getBomSource())) {
      return assessment(context, List.of(), "图库来源与本产品、月份或物料组织不一致");
    }
    // 与混合组树使用同一来源版本生效日；未记录时按本核算月首日检查。
    LocalDate effectiveDate = version.getEffectiveFrom() == null
        ? YearMonth.parse(context.accountingMonth()).atDay(1) : version.getEffectiveFrom();
    var rows = sources.findByVersionId(context.sourceVersionId());
    if (rows.isEmpty()) return assessment(context, List.of(), "当前图库来源版本没有明细，请重新检查");
    var bySequence = new HashMap<String, ElectronicDrawingSourceNode>();
    var ids = new HashSet<Long>();
    for (var row : rows) {
      if (row.getId() == null || !ids.add(row.getId()) || row.getSourceSequence() == null
          || bySequence.putIfAbsent(row.getSourceSequence(), row) != null) {
        return assessment(context, List.of(), "图库来源节点身份缺失或重复，请重新核实来源");
      }
    }
    for (var row : rows) {
      var seen = new HashSet<String>();
      for (var current = row; current != null; ) {
        if (!seen.add(current.getSourceSequence())) return assessment(context, List.of(), "图库来源存在循环父子关系");
        String parent = current.getParentSourceSequence();
        if (parent == null || parent.isBlank()) break;
        current = bySequence.get(parent);
        if (current == null) return assessment(context, List.of(), "图库来源存在未找到父节点的明细");
      }
    }
    Set<String> codes = rows.stream().map(ElectronicDrawingSourceNode::getResolvedMaterialCode)
        .filter(value -> value != null && !value.isBlank()).collect(Collectors.toSet());
    Map<String, List<MaterialMasterRaw>> catalog = codes.isEmpty() ? Map.of()
        : materials.selectByLatestBatchAndCodes(codes, null, context.materialOrgCode()).stream()
            .collect(Collectors.groupingBy(row -> normalized(row.getMaterialCode())));
    Map<String, List<ElectronicDrawingSourceNode>> children = new LinkedHashMap<>();
    var queue = new ArrayDeque<ElectronicDrawingSourceNode>();
    for (var row : rows) {
      if (row.getParentSourceSequence() == null || row.getParentSourceSequence().isBlank()) queue.add(row);
      else children.computeIfAbsent(row.getParentSourceSequence(), ignored -> new ArrayList<>()).add(row);
    }
    if (queue.isEmpty()) return assessment(context, List.of(), "图库明细缺少根节点，请核实来源");
    var result = new ArrayList<Node>();
    var cache = new HashMap<String, SubBomResult>();
    while (!queue.isEmpty()) {
      var source = queue.removeFirst();
      var descendants = children.getOrDefault(source.getSourceSequence(), List.of());
      String code = source.getResolvedMaterialCode();
      if (!Set.of(ElectronicDrawingSourceNode.MATCH_AUTO, ElectronicDrawingSourceNode.MATCH_MANUAL)
          .contains(Objects.toString(source.getMatchStatus(), "")) || normalized(code).isEmpty()) {
        result.add(node(source, State.WAIT_FINANCE, "待财务确认 U9 料号，确认后再检查下级关系", List.of()));
        continue;
      }
      var matches = catalog.getOrDefault(normalized(code), List.of());
      if (matches.size() != 1) {
        result.add(node(source, State.ERROR, "当前物料组织未找到唯一料品档案，请财务核实", List.of()));
        continue;
      }
      Nature nature;
      try { nature = Nature.parse(matches.getFirst().getShapeAttr()); }
      catch (IllegalArgumentException | com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException error) {
        result.add(node(source, State.ERROR, "料品形态不明确，请核实料品档案", List.of()));
        continue;
      }
      if (nature == Nature.PURCHASE) {
        if (!descendants.isEmpty()) result.add(node(source, State.ERROR, "采购件包含下级，须先核实图库结构", List.of()));
        continue;
      }
      SubBomResult bom = cache.computeIfAbsent(normalized(code), ignored -> query(context, code, effectiveDate));
      switch (bom.status()) {
        case AVAILABLE -> {
          if (bom.nodes().isEmpty() || !normalized(code).equals(normalized(bom.parentMaterialCode()))
              || !Objects.equals(context.priceOrgCode(), bom.priceOrgCode())
              || !Objects.equals(context.materialOrgCode(), bom.materialOrganizationCode())) {
            result.add(node(source, State.ERROR, "U9 下级返回的产品、组织或明细无效，请核实来源", List.of()));
          } else result.add(node(source, State.U9_READY, "已沿用 U9 下级关系，无需补录", bom.nodes()));
          // U9 子树替换该节点的图库后代，与既有组树一致，不能再次要求技术补被替换的行。
        }
        case NOT_FOUND -> {
          if (!descendants.isEmpty()) {
            result.add(node(source, State.DRAWING_READY, "已有图库下级关系，继续检查下级节点", List.of()));
            queue.addAll(descendants);
          } else if (nature == Nature.MANUFACTURE) {
            result.add(node(source, State.MISSING_RAW, "U9 已明确无下级关系，请补一种采购原材料", List.of()));
          } else result.add(node(source, State.ERROR, "委外或虚拟件缺少下级，请维护对应 BOM", List.of()));
        }
        default -> result.add(node(source, State.ERROR,
            bom.message() == null ? "U9 下级查询尚未取得明确结论" : bom.message(), List.of()));
      }
    }
    return assessment(context, result, null);
  }

  private SubBomResult query(ElectronicDrawingWorkContext context, String parentCode, LocalDate date) {
    SubBomResult result = u9.query(new SubBomQuery(context.oaNo(), context.oaFormItemId(), parentCode,
        context.accountingMonth(), context.priceOrgCode(), context.materialOrgCode(), context.businessUnitType(), null, date));
    return result == null || result.status() == null
        ? SubBomResult.failure(ElectronicDrawingU9SubBomPort.Status.ERROR, parentCode, "U9 下级查询返回空结果") : result;
  }

  private Assessment assessment(ElectronicDrawingWorkContext context, List<Node> nodes, String reason) {
    var immutable = List.copyOf(nodes);
    String hash = codec.dataFingerprint(new Assessment(context.sourceVersionId(), context.accountingMonth(), null, immutable, reason));
    return new Assessment(context.sourceVersionId(), context.accountingMonth(), hash, immutable, reason);
  }

  public boolean matchesFingerprint(Assessment assessment, String expected) {
    return codec.matchesDataFingerprint(expected, new Assessment(assessment.sourceVersionId(),
        assessment.accountingMonth(), null, assessment.nodes(), assessment.unavailableReason()));
  }

  private Node node(ElectronicDrawingSourceNode row, State state, String message, List<U9Node> children) {
    return new Node(row.getId(), row.getSourceSequence(), row.getParentSourceSequence(), row.getSourceName(),
        row.getDrawingCode(), row.getMaterial(), row.getQty(), row.getReferenceWeight(), row.getReferenceWeightUnit(),
        row.getResolvedMaterialCode(), state, message, List.copyOf(children));
  }

  private static String normalized(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
}
