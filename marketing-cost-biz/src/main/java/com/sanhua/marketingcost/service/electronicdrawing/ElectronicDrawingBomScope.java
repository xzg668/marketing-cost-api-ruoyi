package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomPolicyActionResolver;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomShapeDecision;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.ElectronicNode;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.MaterialSnapshot;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.SubBomQuery;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.SubBomResult;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeRequest;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeResolver;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeSource;
import com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeResolver;
import com.sanhua.marketingcost.service.rule.BomRuleNodeContext;
import com.sanhua.marketingcost.service.rule.BomRuleMaterialAttributeResolver;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 财务料号确认、制造缺口检查和组树共用的图库可达范围；源 Excel 节点始终保留。 */
@Service
public class ElectronicDrawingBomScope {
  public static final String DRAWING_NODE = "E_DRAWING_NODE";
  public enum State { PURCHASE, U9_READY, DRAWING_READY, MISSING_RAW, WAIT_FINANCE, EXCLUDED, ERROR }
  public record Branch(ElectronicDrawingSourceNode source, MaterialSnapshot material,
      State state, String message, SubBomResult subBom) {}
  public record Plan(List<ElectronicNode> nodes, Map<Long, Branch> branches, Set<Long> pendingIds) {}

  private final MaterialMasterRawMapper materials;
  private final ElectronicDrawingU9SubBomPort u9;
  private final MaterialQuoteShapeResolver shapes;
  private final SupplierRatioShapeResolver suppliers;
  private final EffectiveBomPolicyActionResolver actions;
  private final BomSettlementRuleQueryService rules;
  private final BomSettlementRuleMatcher matcher;
  private final BomRuleMaterialAttributeResolver attributes;

  public ElectronicDrawingBomScope(MaterialMasterRawMapper materials, ElectronicDrawingU9SubBomPort u9,
      MaterialQuoteShapeResolver shapes, SupplierRatioShapeResolver suppliers,
      EffectiveBomPolicyActionResolver actions, BomSettlementRuleQueryService rules,
      BomSettlementRuleMatcher matcher, BomRuleMaterialAttributeResolver attributes) {
    this.materials = materials; this.u9 = u9; this.shapes = shapes; this.suppliers = suppliers;
    this.actions = actions; this.rules = rules; this.matcher = matcher; this.attributes = attributes;
  }

  public Plan inspect(ElectronicDrawingWorkContext context, List<ElectronicDrawingSourceNode> rows,
      LocalDate date) {
    var bySequence = new LinkedHashMap<String, ElectronicDrawingSourceNode>();
    var ids = new HashSet<Long>();
    for (var row : rows) {
      if (row.getId() == null || !ids.add(row.getId()) || row.getSourceSequence() == null
          || bySequence.putIfAbsent(row.getSourceSequence(), row) != null)
        throw new IllegalArgumentException("图库来源节点身份缺失或重复");
    }
    var children = new LinkedHashMap<String, List<ElectronicDrawingSourceNode>>();
    var roots = new ArrayList<ElectronicDrawingSourceNode>();
    for (var row : rows) {
      var seen = new HashSet<String>();
      for (var current = row; current != null;) {
        if (!seen.add(current.getSourceSequence())) throw new IllegalArgumentException("图库存在循环父子关系");
        String parent = text(current.getParentSourceSequence());
        if (parent == null) break;
        current = bySequence.get(parent);
        if (current == null) throw new IllegalArgumentException("图库存在未找到父节点的明细");
      }
      if (text(row.getParentSourceSequence()) == null) roots.add(row);
      else children.computeIfAbsent(row.getParentSourceSequence(), ignored -> new ArrayList<>()).add(row);
    }
    if (roots.isEmpty()) throw new IllegalArgumentException("图库明细缺少根节点");
    Set<String> codes = rows.stream().map(ElectronicDrawingSourceNode::getResolvedMaterialCode)
        .filter(code -> text(code) != null).collect(Collectors.toCollection(LinkedHashSet::new));
    var catalog = codes.isEmpty() ? Map.<String, List<MaterialMasterRaw>>of()
        : materials.selectByLatestBatchAndCodes(codes, null, context.materialOrgCode()).stream()
            .collect(Collectors.groupingBy(row -> row.getMaterialCode().toUpperCase(java.util.Locale.ROOT)));
    var requests = catalog.values().stream().filter(found -> found.size() == 1).map(found -> {
      var raw = found.getFirst();
      return new MaterialQuoteShapeRequest(context.materialOrgCode(), raw.getMaterialCode(),
          context.accountingMonth(), raw.getShapeAttr());
    }).toList();
    var resolved = shapes.resolveAll(requests);
    var supplierResults = suppliers.resolveAll(resolved.values().stream()
        .filter(result -> result.source() == MaterialQuoteShapeSource.SUPPLIER_RATIO).toList());
    var decisions = new HashMap<String, EffectiveBomShapeDecision>();
    resolved.forEach((code, result) -> decisions.put(code, result.source() == MaterialQuoteShapeSource.SUPPLIER_RATIO
        ? supplierResults.containsKey(code) ? EffectiveBomShapeDecision.from(supplierResults.get(code), result.sourceU9Shape())
            : EffectiveBomShapeDecision.blocked(code, "供货比例形态尚未确定")
        : EffectiveBomShapeDecision.from(result)));
    var attr = attributes.resolve(codes, context.materialOrgCode());
    var excludes = rules.listEnabledCandidates().stream()
        .filter(rule -> "EXCLUDE".equals(rule.getSettlementAction())).toList();
    var branches = new LinkedHashMap<Long, Branch>();
    var pending = new LinkedHashSet<Long>();
    var retained = new ArrayList<ElectronicNode>();
    var cache = new HashMap<String, SubBomResult>();
    class Walker {
      MaterialSnapshot snapshot(ElectronicDrawingSourceNode row) {
        String code = text(row.getResolvedMaterialCode());
        if (code != null && Set.of(ElectronicDrawingSourceNode.MATCH_AUTO, ElectronicDrawingSourceNode.MATCH_MANUAL)
            .contains(Objects.toString(row.getMatchStatus(), ""))) {
          var found = catalog.getOrDefault(code.toUpperCase(java.util.Locale.ROOT), List.of());
          if (found.size() != 1) return null;
          var raw = found.getFirst();
          return new MaterialSnapshot(raw.getMaterialCode(), raw.getMaterialName(), raw.getMaterialSpec(),
              raw.getMaterialModel(), row.getDrawingCode(), raw.getShapeAttr(), raw.getMainCategoryCode(),
              raw.getProductionCategory(), raw.getCostElement(), raw.getUnit());
        }
        // 无唯一 U9 对应的装配/自制结构采用图库下级。多候选仍必须由财务确认。
        // DRAWING: 是报价内部身份，resolved_material_code 保持为空，不写入 U9 料品档案。
        if (ElectronicDrawingSourceNode.MATCH_UNMATCHED.equals(row.getMatchStatus())
            && !children.getOrDefault(row.getSourceSequence(), List.of()).isEmpty())
          return new MaterialSnapshot(QuoteProductIdentityUtils.resolveCostingCode(null, null, row.getDrawingCode()),
              row.getSourceName(), row.getMaterial(), null, row.getDrawingCode(), "制造件", null,
              DRAWING_NODE, null, "件");
        return null;
      }
      BomRuleNodeContext ruleContext(ElectronicDrawingSourceNode row, MaterialSnapshot material) {
        var extra = material == null || attr == null ? null : attr.get(material.materialCode());
        return new BomRuleNodeContext(material == null ? row.getResolvedMaterialCode() : material.materialCode(),
            material == null ? row.getSourceName() : material.materialName(), material == null ? null : material.mainCategoryCode(),
            extra == null ? null : extra.mainCategoryCode(), null, extra == null ? null : extra.purchaseCategory(),
            material == null ? null : material.shapeAttr(), material == null ? null : material.costElementCode(),
            material == null ? null : material.sourceCategory(), context.businessUnitType(), "主制造");
      }
      void exclude(ElectronicDrawingSourceNode row, String reason) {
        branches.put(row.getId(), new Branch(row, snapshot(row), State.EXCLUDED, reason, null));
        for (var child : children.getOrDefault(row.getSourceSequence(), List.of())) exclude(child, reason);
      }
      void visit(ElectronicDrawingSourceNode row, BomRuleNodeContext parent, Set<String> excludedCodes) {
        var lower = children.getOrDefault(row.getSourceSequence(), List.of());
        var material = snapshot(row);
        var nodeContext = ruleContext(row, material);
        var hit = matcher.match(nodeContext, parent, lower.stream().map(child -> ruleContext(child, snapshot(child))).toList(),
            "主制造", date, excludes);
        boolean auxiliaryLeaf = material != null && lower.isEmpty() && "采购件".equals(material.shapeAttr());
        if ((text(row.getResolvedMaterialCode()) != null && excludedCodes.contains(row.getResolvedMaterialCode()))
            || (hit.isPresent() && (!"AUXILIARY_EXCLUDE".equals(hit.get().getRuleCategory()) || auxiliaryLeaf))) {
          exclude(row, "按现有核算规则排除，无需确认料号或补原材料"); return;
        }
        if (material == null) {
          pending.add(row.getId());
          branches.put(row.getId(), new Branch(row, null, State.WAIT_FINANCE,
              "待财务确认 U9 料号，确认后检查实际下级", null)); return;
        }
        var decision = DRAWING_NODE.equals(material.sourceCategory())
            ? EffectiveBomShapeDecision.u9(material.materialCode(), material.shapeAttr(), QuoteMaterialShape.MANUFACTURE)
            : decisions.get(material.materialCode());
        if (decision == null || decision.blocked()) {
          branches.put(row.getId(), new Branch(row, material, State.ERROR,
              decision == null ? "物料形态未确定" : decision.blockingReason(), null)); return;
        }
        retained.add(new ElectronicNode(row.getId(), row.getSourceRowNo(), row.getSourceSequence(),
            row.getParentSourceSequence(), row.getDrawingCode(), row.getSourceName(), row.getQty(),
            row.getReferenceWeight(), row.getReferenceWeightUnit(), row.getMatchStatus(), material,
            ElectronicDrawingHybridBomAssembler.Nature.valueOf(decision.effectiveShape().name())));
        if (decision.effectiveShape() == QuoteMaterialShape.PURCHASE) {
          branches.put(row.getId(), new Branch(row, material, State.PURCHASE, "采购件直接取价，不展开下级", null));
          for (var child : lower) exclude(child, "采购形态已截断下级，无需补录"); return;
        }
        var action = decision.effectiveShape() == QuoteMaterialShape.OUTSOURCE
            ? actions.resolve(decision).excludedDirectChildMaterialCodes() : Set.<String>of();
        SubBomResult bom = null;
        if (!DRAWING_NODE.equals(material.sourceCategory())) {
          bom = cache.computeIfAbsent(material.materialCode(), code -> u9.query(new SubBomQuery(context.oaNo(),
              context.oaFormItemId(), code, context.accountingMonth(), context.priceOrgCode(), context.materialOrgCode(),
              context.businessUnitType(), null, date)));
          if (bom == null || bom.status() == null) throw new IllegalStateException("U9 下级查询返回空结果");
          if (bom.status() == ElectronicDrawingU9SubBomPort.Status.AVAILABLE) {
            if (bom.nodes().isEmpty() || !Objects.equals(material.materialCode(), bom.parentMaterialCode())
                || !Objects.equals(context.priceOrgCode(), bom.priceOrgCode())
                || !Objects.equals(context.materialOrgCode(), bom.materialOrganizationCode()))
              throw new IllegalStateException("U9 下级返回的产品、组织或明细无效");
            branches.put(row.getId(), new Branch(row, material, State.U9_READY, "沿用 U9 下级，无需补录", bom));
            for (var child : lower) exclude(child, "已由 U9 下级替换，无需确认原图库行"); return;
          }
          if (bom.status() != ElectronicDrawingU9SubBomPort.Status.NOT_FOUND) {
            branches.put(row.getId(), new Branch(row, material, State.ERROR,
                bom.message() == null ? "U9 下级查询未取得明确结论" : bom.message(), bom)); return;
          }
        }
        boolean hasRetainedChild = lower.stream().anyMatch(child -> text(child.getResolvedMaterialCode()) == null
            || !action.contains(child.getResolvedMaterialCode()));
        if (hasRetainedChild) {
          branches.put(row.getId(), new Branch(row, material, State.DRAWING_READY,
              DRAWING_NODE.equals(material.sourceCategory()) ? "无 U9 料号，保留图号并沿图库下级处理" : "沿图库下级继续检查", bom));
          for (var child : lower) visit(child, nodeContext, action);
        } else {
          var state = decision.effectiveShape() == QuoteMaterialShape.MANUFACTURE ? State.MISSING_RAW : State.ERROR;
          branches.put(row.getId(), new Branch(row, material, state,
              state == State.MISSING_RAW ? "实际保留的制造件缺原材料关系，请补录" : "委外或虚拟件缺少可用下级", bom));
          for (var child : lower) exclude(child, "形态规则已排除该下级");
        }
      }
    }
    var walker = new Walker();
    for (var root : roots) walker.visit(root, null, Set.of());
    return new Plan(List.copyOf(retained), Map.copyOf(branches), Set.copyOf(pending));
  }

  private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
