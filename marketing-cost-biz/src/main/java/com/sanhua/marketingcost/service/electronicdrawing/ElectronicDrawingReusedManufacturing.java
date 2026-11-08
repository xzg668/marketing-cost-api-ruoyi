package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomReadScope;
import com.sanhua.marketingcost.service.technicaldata.ElectronicDrawingManufacturingInputs;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.RawMaterial;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSubmissionState;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec;
import com.sanhua.marketingcost.service.technicaldata.TechnicalManufacturingInputs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 从已确认完整 BOM 中读取它实际采用的制造件补录，按未变化节点接到本次来源。 */
@Service
public class ElectronicDrawingReusedManufacturing {
  public record Reused(Long nodeId, Long technicalVersionId, String itemKey, RawMaterial input) {}
  private final QuoteBomSupplementVersionMapper versions;
  private final QuoteBomSupplementDetailMapper details;
  private final ElectronicDrawingSourceNodeRepository nodes;
  private final QuoteTechDataVersionMapper technicalVersions;
  private final TechnicalDataVersionContentCodec content;
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final MonthlyRepriceBomReadScope repriceBom;
  private final ElectronicDrawingManufacturingInputs drawingInputs;

  public ElectronicDrawingReusedManufacturing(QuoteBomSupplementVersionMapper versions,
      QuoteBomSupplementDetailMapper details, ElectronicDrawingSourceNodeRepository nodes,
      QuoteTechDataVersionMapper technicalVersions, TechnicalDataVersionContentCodec content,
      ElectronicDrawingWorkflowContextPort contexts,
      MonthlyRepriceBomReadScope repriceBom,
      ElectronicDrawingManufacturingInputs drawingInputs) {
    this.versions = versions; this.details = details; this.nodes = nodes;
    this.technicalVersions = technicalVersions; this.content = content; this.contexts = contexts;
    this.repriceBom = repriceBom; this.drawingInputs = drawingInputs;
  }

  public boolean verifiedRevision(ElectronicDrawingWorkContext context) {
    return !lineage(context).isEmpty();
  }

  private List<QuoteBomSupplementVersion> lineage(ElectronicDrawingWorkContext context) {
    if (context == null || context.sourceVersionId() == null) return List.of();
    var current = versions.selectById(context.sourceVersionId());
    if (current == null || current.getDrawingResultId() == null) return List.of();
    var chain = new ArrayList<QuoteBomSupplementVersion>();
    var seen = new java.util.HashSet<Long>();
    var next = current;
    while (next != null) {
      if (!seen.add(next.getId())) throw new IllegalArgumentException("图库复用来源存在循环");
      if (!Objects.equals(current.getQuoteProductCode(), next.getQuoteProductCode())
          || !Objects.equals(current.getMaterialOrgCode(), next.getMaterialOrgCode()))
        throw new IllegalArgumentException("复用制造件的产品或物料组织不一致");
      chain.add(next);
      if (chain.size() > 1 && "APPROVED".equals(next.getVersionStatus())
          && !Objects.equals(current.getDrawingResultId(), next.getDrawingResultId())) return List.copyOf(chain);
      next = next.getReusedFromVersionId() == null ? null : versions.selectById(next.getReusedFromVersionId());
    }
    return List.of();
  }

  public List<Reused> read(ElectronicDrawingWorkContext context) {
    var chain = lineage(context);
    if (chain.isEmpty()) return List.of();
    var current = chain.getFirst();
    var previous = chain.getLast();
    var ancestry = new LinkedHashMap<Long, ElectronicDrawingSourceNode>();
    var originalIds = new java.util.HashSet<Long>();
    for (var version : chain) for (var node : nodes.findByVersionId(version.getId())) {
      ancestry.put(node.getId(), node);
      if (version.getId().equals(previous.getId())) originalIds.add(node.getId());
    }
    var oldRows = rows(previous.getId());
    var result = new ArrayList<Reused>();
    for (var node : nodes.findByVersionId(current.getId())) {
      Long originalId = node.getCorrectedFromSourceNodeId();
      var seen = new java.util.HashSet<Long>();
      while (originalId != null && !originalIds.contains(originalId)) {
        if (!seen.add(originalId)) throw new IllegalArgumentException("制造件来源节点存在循环");
        var ancestor = ancestry.get(originalId);
        originalId = ancestor == null ? null : ancestor.getCorrectedFromSourceNodeId();
      }
      if (originalId == null) continue;
      var original = ancestry.get(originalId);
      if (original == null || !sameWeight(node, original)) continue;
      Long sourceNodeId = originalId;
      var parents = oldRows.stream().filter(row -> "E_DRAWING".equals(row.getNodeSourceType())
          && Objects.equals(row.getSourceElectronicNodeId(), sourceNodeId)).toList();
      if (parents.size() != 1) continue;
      var parent = parents.getFirst();
      if (node.getResolvedMaterialCode() != null && !Objects.equals(parent.getMaterialCode(), node.getResolvedMaterialCode())) continue;
      var children = oldRows.stream().filter(row -> "TECH_RAW".equals(row.getNodeSourceType())
          && Objects.equals(row.getLevel(), parent.getLevel() + 1)
          && row.getPath().startsWith(parent.getPath())).toList();
      if (children.isEmpty()) continue;
      if (children.size() != 1) throw new IllegalArgumentException("已确认制造件存在多条补录原料，不能任取一条复用");
      var child = children.getFirst();
      String key = child.getPath().substring(parent.getPath().length());
      var matcher = java.util.regex.Pattern.compile("TECH:([0-9]+):([^/]+)/").matcher(key);
      if (!matcher.matches()) throw new IllegalArgumentException("已确认制造件缺少补录版本依据");
      Long technicalId = Long.valueOf(matcher.group(1));
      var version = technicalVersions.selectById(technicalId);
      if (version == null || !TechnicalDataSubmissionState.submitted(version.getVersionStatus()))
        throw new IllegalArgumentException("原制造件补录没有有效提交版本");
      var manufacturing = content.manufacturing(version);
      var matching = manufacturing == null ? List.<RawMaterial>of() : manufacturing.items().stream()
          .filter(row -> matcher.group(2).equals(row.itemKey())
              && parent.getMaterialCode().equals(row.parentMaterialNo())
              && child.getMaterialCode().equals(row.rawMaterialNo())
              && Objects.equals(child.getUnit(), row.unit())
              && child.getQtyPerParent().compareTo(row.quantityPerParent()) == 0).toList();
      if (matching.size() != 1) throw new IllegalArgumentException("已确认 BOM 与原制造件补录内容不一致");
      result.add(new Reused(node.getId(), technicalId, matcher.group(2), matching.getFirst()));
    }
    return List.copyOf(result);
  }

  public Map<String, TechnicalManufacturingInputs.Input> costingInputs(Long itemId, String month) {
    var context = repriceBom.costingDrawing(itemId, month,
        () -> contexts.loadForCurrentBusinessUnit(itemId, month));
    if (context == null || context.sourceVersionId() == null) return Map.of();
    var result = new LinkedHashMap<>(drawingInputs.read(context, null));
    var reused = read(context);
    if (reused.isEmpty()) return Map.copyOf(result);
    var current = rows(context.sourceVersionId());
    for (var input : reused) {
      var parents = current.stream().filter(row -> "E_DRAWING".equals(row.getNodeSourceType())
          && Objects.equals(row.getSourceElectronicNodeId(), input.nodeId())).toList();
      if (parents.size() != 1) continue;
      var parent = parents.getFirst();
      var children = current.stream().filter(row -> Objects.equals(row.getLevel(), parent.getLevel() + 1)
          && row.getPath().startsWith(parent.getPath())).toList();
      if (children.size() != 1 || !Objects.equals(children.getFirst().getMaterialCode(), input.input().rawMaterialNo())
          || !Objects.equals(children.getFirst().getUnit(), input.input().unit())
          || children.getFirst().getQtyPerParent() == null
          || children.getFirst().getQtyPerParent().compareTo(input.input().quantityPerParent()) != 0)
        continue; // 本次已经采用其他关系时，原补录不再参与本次计算。
      var raw = children.getFirst();
      if (parent.getQtyPerTop() == null || raw.getQtyPerTop() == null
          || raw.getQtyPerTop().compareTo(parent.getQtyPerTop().multiply(raw.getQtyPerParent())) != 0)
        throw new IllegalArgumentException("制造件原料总用量与父件数量不一致，请重新检查 BOM");
      result.put(parent.getPath(), new TechnicalManufacturingInputs.Input(input.technicalVersionId(), parent.getPath(), input.input()));
    }
    return Map.copyOf(result);
  }

  private boolean sameWeight(ElectronicDrawingSourceNode current,
      ElectronicDrawingSourceNode original) {
    return Objects.equals(current.getReferenceWeightUnit(), original.getReferenceWeightUnit())
        && (current.getReferenceWeight() == null ? original.getReferenceWeight() == null
            : original.getReferenceWeight() != null
                && current.getReferenceWeight().compareTo(original.getReferenceWeight()) == 0);
  }

  private List<QuoteBomSupplementDetail> rows(Long id) {
    return details.selectList(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, id).orderByAsc(QuoteBomSupplementDetail::getLineNo));
  }
}
