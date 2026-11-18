package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.Nature;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.U9Node;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingSourceQuery.Node;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingSourceQuery.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.util.StringUtils;

/** 制造件预览和缺口检查优先读取本次已确认的完整树，不能用新 ODS 推翻已采用的下级。 */
public class TechnicalDataManufacturingAdoptedBom {
  private final QuoteBomSupplementDetailMapper details;

  public TechnicalDataManufacturingAdoptedBom(QuoteBomSupplementDetailMapper details) {
    this.details = details;
  }

  /** 未确认的来源仍走实时检查；已确认但损坏的树必须明确报错，不能假装没有缺口。 */
  public List<Node> read(ElectronicDrawingWorkContext context, QuoteBomSupplementVersion version,
      List<ElectronicDrawingSourceNode> sources) {
    if (!"APPROVED".equals(version.getVersionStatus())
        || !StringUtils.hasText(version.getCompositionFingerprint())) return null;
    if (!Objects.equals(version.getQuoteProductCode(), context.quoteProductCode()))
      throw new IllegalStateException("已确认 BOM 与本产品不一致");
    var rows = details.selectList(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, version.getId())
        .orderByAsc(QuoteBomSupplementDetail::getLineNo));
    var byPath = validatedTree(context, version.getId(), rows);
    var children = new HashMap<String, List<QuoteBomSupplementDetail>>();
    var anchors = new HashMap<Long, QuoteBomSupplementDetail>();
    for (var row : rows) {
      if (row.getLevel() > 0) children.computeIfAbsent(parentPath(row.getPath()), ignored -> new ArrayList<>()).add(row);
      if (row.getSourceElectronicNodeId() != null && "E_DRAWING".equals(row.getNodeSourceType())
          && anchors.putIfAbsent(row.getSourceElectronicNodeId(), row) != null)
        throw new IllegalStateException("已确认 BOM 的图库节点重复");
    }
    var bySequence = new HashMap<String, ElectronicDrawingSourceNode>();
    for (var source : sources) bySequence.put(source.getSourceSequence(), source);
    var result = new ArrayList<Node>();
    for (var source : sources) {
      var row = adoptedRow(source, anchors, bySequence, rows);
      // 已采用整支 U9 或整件采购时，原图纸中未采用的下级不再要求重新匹配。
      if (row == null || Nature.parse(row.getShapeAttr()) == Nature.PURCHASE) continue;
      var descendants = rows.stream().filter(child -> child != row && child.getPath().startsWith(row.getPath())).toList();
      var missing = new ArrayList<String>();
      for (var current : rows) {
        if (current.getLevel() > 0 && current.getPath().startsWith(row.getPath())
            && Nature.parse(current.getShapeAttr()) == Nature.MANUFACTURE
            && children.getOrDefault(current.getPath(), List.of()).isEmpty()) missing.add(current.getMaterialCode());
      }
      State state = missing.isEmpty() ? State.U9_READY
          : descendants.isEmpty() && "E_DRAWING".equals(row.getNodeSourceType()) ? State.MISSING_RAW : State.ERROR;
      String message = missing.isEmpty() ? "沿用本单已确认 BOM 的下级关系，无需补录"
          : "已确认 BOM 中制造件缺少下级关系：" + String.join("、", missing);
      var existing = descendants.stream().map(child -> new U9Node(child.getPath(),
          Objects.equals(parentPath(child.getPath()), row.getPath()) ? null : parentPath(child.getPath()),
          child.getSourceRawHierarchyId(), child.getSourceU9BomId(), child.getMaterialCode(),
          child.getMaterialName(), child.getMaterialSpec(), child.getMaterialModel(), child.getDrawingNo(),
          child.getShapeAttr(), child.getMainCategoryCode(), child.getSourceCategory(), child.getCostElementCode(),
          child.getBomPurpose(), child.getBomVersion(), child.getQtyPerParent(), child.getParentBaseQty(),
          child.getUnit(), child.getSortSeq())).toList();
      result.add(new Node(source.getId(), source.getSourceSequence(), source.getParentSourceSequence(),
          source.getSourceName(), source.getDrawingCode(), source.getMaterial(), source.getQty(),
          source.getReferenceWeight(), source.getReferenceWeightUnit(), row.getMaterialCode(), state, message, existing));
    }
    if (result.isEmpty() && byPath.values().stream().anyMatch(row -> row.getLevel() > 0
        && Nature.parse(row.getShapeAttr()) == Nature.MANUFACTURE))
      throw new IllegalStateException("已确认 BOM 的制造件缺少图库节点对应关系");
    return List.copyOf(result);
  }

  private QuoteBomSupplementDetail adoptedRow(ElectronicDrawingSourceNode source,
      Map<Long, QuoteBomSupplementDetail> anchors, Map<String, ElectronicDrawingSourceNode> bySequence,
      List<QuoteBomSupplementDetail> rows) {
    var direct = anchors.get(source.getId());
    if (direct != null) return direct;
    var seen = new HashSet<String>();
    var parent = bySequence.get(source.getParentSourceSequence());
    while (parent != null) {
      if (!seen.add(parent.getSourceSequence())) throw new IllegalStateException("图库来源存在循环父子关系");
      var anchor = anchors.get(parent.getId());
      if (anchor != null) {
        if (Nature.parse(anchor.getShapeAttr()) == Nature.PURCHASE) return null;
        var matches = rows.stream().filter(row -> "U9_EXPANDED".equals(row.getNodeSourceType())
            && row.getPath().startsWith(anchor.getPath())
            && Objects.equals(row.getMaterialCode(), source.getResolvedMaterialCode())).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
      }
      parent = bySequence.get(parent.getParentSourceSequence());
    }
    return null;
  }

  private Map<String, QuoteBomSupplementDetail> validatedTree(ElectronicDrawingWorkContext context,
      Long versionId, List<QuoteBomSupplementDetail> rows) {
    if (rows == null || rows.isEmpty()) throw new IllegalStateException("已确认的图库 BOM 明细为空");
    var byPath = new LinkedHashMap<String, QuoteBomSupplementDetail>();
    for (var row : rows) {
      if (!Objects.equals(row.getSupplementVersionId(), versionId)
          || !Objects.equals(row.getOaFormItemId(), context.oaFormItemId())
          || !Objects.equals(row.getQuoteProductCode(), context.quoteProductCode())
          || row.getLevel() == null || row.getLevel() < 0 || row.getPath() == null
          || !row.getPath().startsWith("/") || !row.getPath().endsWith("/")
          || byPath.putIfAbsent(row.getPath(), row) != null)
        throw new IllegalStateException("已确认 BOM 的归属或节点路径不一致");
    }
    var roots = rows.stream().filter(row -> row.getLevel() == 0).toList();
    if (roots.size() != 1 || !Objects.equals(roots.getFirst().getMaterialCode(), context.quoteProductCode()) || rows.size() == 1)
      throw new IllegalStateException("已确认 BOM 缺少唯一的产品根节点或下级");
    for (var row : rows) {
      if (row.getLevel() == 0) continue;
      var parent = byPath.get(parentPath(row.getPath()));
      if (parent == null || parent.getLevel() + 1 != row.getLevel()
          || !Objects.equals(parent.getMaterialCode(), row.getParentCode()))
        throw new IllegalStateException("已确认 BOM 存在未接入的下级节点");
    }
    return byPath;
  }

  private static String parentPath(String path) {
    int end = path.lastIndexOf('/', path.length() - 2);
    return path.substring(0, end + 1);
  }
}
