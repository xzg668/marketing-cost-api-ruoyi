package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceKind;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingBomScope;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.U9Node;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
  private final ElectronicDrawingBomScope scope;
  private final OaMessageCodec codec;
  private final QuoteBomSupplementVersionMapper versions;
  private final MaterialMasterRawMapper materials;

  public TechnicalDataManufacturingSourceQuery(ElectronicDrawingSourceNodeRepository sources,
      ElectronicDrawingBomScope scope, OaMessageCodec codec,
      QuoteBomSupplementVersionMapper versions, MaterialMasterRawMapper materials) {
    this.sources = sources; this.scope = scope; this.codec = codec; this.versions = versions;
    this.materials = materials;
  }

  public Assessment inspect(ElectronicDrawingWorkContext context) {
    if (context.sourceVersionId() == null) return assessment(context, List.of(), "请先取得本产品的电子图库明细");
    var version = versions.selectById(context.sourceVersionId());
    if (version == null || !Objects.equals(version.getOaFormItemId(), context.oaFormItemId())
        || !Objects.equals(version.getPeriodMonth(), context.accountingMonth())
        || !Objects.equals(version.getMaterialOrgCode(), context.materialOrgCode())
        || !Objects.equals(version.getActiveFlag(), 1)
        || !ElectronicDrawingSourceKind.isDrawing(version.getBomSource())) {
      return assessment(context, List.of(), "图库来源与本产品、月份或物料组织不一致");
    }
    // 与混合组树使用同一来源版本生效日；未记录时按本核算月首日检查。
    LocalDate effectiveDate = version.getEffectiveFrom() == null
        ? YearMonth.parse(context.accountingMonth()).atDay(1) : version.getEffectiveFrom();
    var rows = sources.findByVersionId(context.sourceVersionId());
    if (rows.isEmpty()) return assessment(context, List.of(), "当前图库来源版本没有明细，请重新检查");
    try {
      var plan = scope.inspect(context, rows, effectiveDate);
      var materialWeights = missingDrawingWeights(context, plan);
      var result = new ArrayList<Node>();
      for (var source : rows) {
        var branch = plan.branches().get(source.getId());
        if (branch == null || branch.state() == ElectronicDrawingBomScope.State.EXCLUDED
            || branch.state() == ElectronicDrawingBomScope.State.PURCHASE) continue;
        State state = State.valueOf(branch.state().name());
        var children = branch.subBom() == null ? List.<U9Node>of() : branch.subBom().nodes();
        BigDecimal netWeight = source.getReferenceWeight();
        String weightUnit = source.getReferenceWeightUnit();
        if (netWeight == null && branch.material() != null) {
          netWeight = materialWeights.get(branch.material().materialCode());
          weightUnit = netWeight == null ? null : "g";
        }
        result.add(new Node(source.getId(), source.getSourceSequence(), source.getParentSourceSequence(),
            source.getSourceName(), source.getDrawingCode(), source.getMaterial(), source.getQty(),
            netWeight, weightUnit,
            branch.material() == null ? source.getResolvedMaterialCode() : branch.material().materialCode(),
            state, branch.message(), List.copyOf(children)));
      }
      return assessment(context, result, null);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return assessment(context, List.of(), exception.getMessage());
    }
  }

  /** 图号已匹配的制造件缺少图库净重时，按本物料组织读取其 U9 理论净重（g）。 */
  private Map<String, BigDecimal> missingDrawingWeights(ElectronicDrawingWorkContext context,
      ElectronicDrawingBomScope.Plan plan) {
    var codes = plan.branches().values().stream()
        .filter(branch -> branch.source().getReferenceWeight() == null && branch.material() != null)
        .filter(branch -> branch.state() != ElectronicDrawingBomScope.State.EXCLUDED
            && branch.state() != ElectronicDrawingBomScope.State.PURCHASE)
        .map(branch -> branch.material().materialCode()).distinct().toList();
    if (codes.isEmpty()) return Map.of();
    var result = new LinkedHashMap<String, BigDecimal>();
    for (MaterialMasterRaw material : materials.selectByLatestBatchAndCodes(codes, null, context.materialOrgCode())) {
      String value = material.getGlobalSeg3TheoreticalNetWeight();
      if (value == null || value.isBlank()) continue;
      try {
        BigDecimal weight = new BigDecimal(value.trim().replace(",", ""));
        if (weight.signum() > 0) result.put(material.getMaterialCode(), weight);
      } catch (NumberFormatException ignored) {
        // 无效的档案重量保持缺失，不能用零代替。
      }
    }
    return result;
  }

  public String sourceKind(Long sourceVersionId) {
    var source = versions.selectById(sourceVersionId);
    if (source == null || !ElectronicDrawingSourceKind.isDrawing(source.getBomSource())) {
      throw new IllegalArgumentException("电子图库来源版本不存在");
    }
    return source.getBomSource();
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

}
