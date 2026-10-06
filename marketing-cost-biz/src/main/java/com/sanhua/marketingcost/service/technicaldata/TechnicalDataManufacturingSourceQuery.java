package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingBomScope;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.U9Node;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
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

  public TechnicalDataManufacturingSourceQuery(ElectronicDrawingSourceNodeRepository sources,
      ElectronicDrawingBomScope scope, OaMessageCodec codec,
      QuoteBomSupplementVersionMapper versions) {
    this.sources = sources; this.scope = scope; this.codec = codec; this.versions = versions;
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
    try {
      var plan = scope.inspect(context, rows, effectiveDate);
      var result = new ArrayList<Node>();
      for (var source : rows) {
        var branch = plan.branches().get(source.getId());
        if (branch == null || branch.state() == ElectronicDrawingBomScope.State.EXCLUDED
            || branch.state() == ElectronicDrawingBomScope.State.PURCHASE) continue;
        State state = State.valueOf(branch.state().name());
        var children = branch.subBom() == null ? List.<U9Node>of() : branch.subBom().nodes();
        result.add(new Node(source.getId(), source.getSourceSequence(), source.getParentSourceSequence(),
            source.getSourceName(), source.getDrawingCode(), source.getMaterial(), source.getQty(),
            source.getReferenceWeight(), source.getReferenceWeightUnit(),
            branch.material() == null ? source.getResolvedMaterialCode() : branch.material().materialCode(),
            state, branch.message(), List.copyOf(children)));
      }
      return assessment(context, result, null);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return assessment(context, List.of(), exception.getMessage());
    }
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
