package com.sanhua.marketingcost.service.pricing.monthly;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.QuoteEffectiveBomNodeMapper;
import com.sanhua.marketingcost.mapper.OaFormItemExtraFieldMapper;
import com.sanhua.marketingcost.service.QuotationMonthlyPriceInputs;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import java.util.*;
import org.springframework.stereotype.Component;

/** Saved price facts are stable; missing prices and unpriced structural ancestors remain observable. */
@Component
public final class MonthlyQuotationInputRevision implements QuotationMonthlyPriceInputs {
  private final PricePrepareItemMapper preparations;
  private final QuoteEffectiveBomNodeMapper nodes;
  private final QuoteCostingWorkspaceService workspaces;
  private final MonthlyMaterialPrices prices;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyPreparationEvidence evidence;
  private final OaFormItemExtraFieldMapper extraFields;

  public MonthlyQuotationInputRevision(PricePrepareItemMapper preparations,
      QuoteEffectiveBomNodeMapper nodes, QuoteCostingWorkspaceService workspaces,
      MonthlyMaterialPrices prices, MonthlyPriceDocumentCodec codec, OaFormItemExtraFieldMapper extraFields) {
    this.preparations = preparations;
    this.nodes = nodes;
    this.workspaces = workspaces;
    this.prices = prices;
    this.codec = codec;
    evidence = new MonthlyPreparationEvidence(codec);
    this.extraFields = extraFields;
  }

  @Override public Scope read(OaForm form, OaFormItem item, String month) {
    var prepared = preparations.selectList(Wrappers.<PricePrepareItem>lambdaQuery()
        .eq(PricePrepareItem::getOaFormItemId, item.getId())
        .eq(PricePrepareItem::getPeriodMonth, month)
        .eq(PricePrepareItem::getCurrentFlag, 1));
    if (prepared.isEmpty()) return Scope.unprepared();
    var workspace = workspaces.find(item.getId(), month).orElse(null);
    List<QuoteEffectiveBomNode> original = workspace == null || workspace.getCurrentBomBuildBatchId() == null
        ? List.of() : nodes.selectList(Wrappers.<QuoteEffectiveBomNode>lambdaQuery()
            .eq(QuoteEffectiveBomNode::getBuildBatchId, workspace.getCurrentBomBuildBatchId()));
    var byPath = new HashMap<String, QuoteEffectiveBomNode>();
    original.forEach(node -> byPath.put(MonthlyBomPricingScope.pathKey(node.getNodePath()), node));
    var rows = new LinkedHashMap<PricePrepareItem, MonthlyPreparationInputs>();
    var ids = new TreeSet<Long>();
    for (var row : prepared) {
      var input = evidence.read(row);
      rows.put(row, input);
      if (input != null) input.prices().forEach(price -> {
        if (price.selection().monthlyPriceId() == null)
          throw new IllegalStateException("正式价格准备缺少已采用月价身份");
        ids.add(price.selection().monthlyPriceId());
      });
    }
    // A pending sibling must not make already adopted inputs depend on current source tables again.
    // Entirely old/unprepared history retains its existing discovery path.
    if (rows.values().stream().allMatch(Objects::isNull)) return Scope.unprepared();
    var saved = ids.isEmpty() ? Map.<Long, MonthlyAdoptionPlan.Selection>of()
        : prices.readByIds(item.getBusinessUnitType(), ids);
    boolean needsAnnual = saved.values().stream().flatMap(value->value.applicability().fragments().stream())
        .anyMatch(fragment->!fragment.annualUsage().equals(AnnualUsageDomain.any()));
    var currentAnnual = needsAnnual ? new AnnualUsageInput(item.getAnnualVolume(), item.getAnnualVolume()==null?null:
        extraFields.selectAnnualVolumeUnit(item.getId())).piecesIfKnown() : null;
    var canonical = new TreeSet<String>();
    var unresolved = new TreeSet<String>();
    var suppressed = new HashSet<String>();
    var boundNodes = new HashSet<String>();
    var boundRequests = new ArrayList<MonthlyPriceRequest>();
    var roots = new HashSet<String>();
    rows.forEach((row, input) -> {
      if (input == null) {
        if (row.getQuantity() == null || row.getQuantity().signum() != 0) {
          unresolved.add(row.getMaterialCode());
          unresolvedComponents(row, unresolved);
        }
        return;
      }
      roots.add(input.rootPath());
      var root = byPath.get(input.rootPath());
      boolean rootChanged = root == null && !byPath.isEmpty() || root != null && (row.getQuantity() == null || root.getQtyPerTop() == null
          || root.getQtyPerTop().compareTo(row.getQuantity()) != 0
          || !Objects.equals(row.getMaterialCode(), root.getMaterialCode())
          || !Objects.equals(row.getPriceOrgCode(), root.getPriceOrgCode()));
      boolean applicable = !rootChanged;
      for (var price : input.prices()) {
        var stored = saved.get(price.selection().monthlyPriceId());
        if (!price.selection().equals(stored)) throw new IllegalStateException("价格准备与保存月价依据不一致");
        var request = price.request();
        var annual = request.annualUsagePieces();
        if (stored.applicability().fragments().stream().anyMatch(fragment -> !fragment.annualUsage().equals(AnnualUsageDomain.any())))
          annual = currentAnnual;
        var current = new MonthlyPriceRequest(request.family(), request.priceOrgCode(),
            conditions(form, request.priceOrgCode()), request.priceBasisQuantity(), annual);
        if (!month.equals(request.family().month().toString())
            || !stored.applicability().matches(current)) applicable = false;
        if (MonthlyPreparationPrice.KIND.equals(row.getResultRefType()) && root != null
            && input.uses().stream().anyMatch(use -> use.path().equals(input.rootPath()) && use.request().equals(request))
            && (!sameNode(root, request) || root.getQtyPerTop().abs().compareTo(request.priceBasisQuantity()) != 0))
          applicable = false;
      }
      if (applicable) {
        canonical.add(MonthlyPreparationFingerprint.of(input));
        suppressed.addAll(input.suppressedPaths());
        input.prices().forEach(price -> boundRequests.add(price.request()));
        for (var use : input.uses()) {
          var node = byPath.get(use.path());
          var request = use.request();
          if (node != null && sameNode(node, request)) boundNodes.add(use.path());
        }
      } else {
        input.prices().forEach(price -> unresolved.add(price.request().family().materialCode()));
        unresolved.add(row.getMaterialCode());
        canonical.add("INPUT_CHANGED:" + input.rootPath());
      }
    });
    var household = new TreeSet<String>();
    var parents = new HashSet<String>();
    original.forEach(node -> { if (node.getParentNodeKey() != null) parents.add(node.getParentNodeKey()); });
    for (var node : original) {
      var path = MonthlyBomPricingScope.pathKey(node.getNodePath());
      if (suppressed.contains(path)) continue;
      canonical.add("BOM:" + codec.unwrap(codec.encode(List.of(path, node.getMaterialCode(),
          Objects.toString(node.getMaterialSpec(), ""), Objects.toString(node.getMaterialUnit(), ""),
          Objects.toString(node.getPriceOrgCode(), ""), Objects.toString(node.getQtyPerTop(), ""),
          Objects.toString(node.getEffectiveMaterialShape(), "")))));
      // A structure parent has no ordinary shared price. Only a household source can replace its children.
      if (!boundNodes.contains(path) && boundRequests.stream().noneMatch(request -> sameNode(node, request)
          && node.getQtyPerTop() != null && node.getQtyPerTop().abs().compareTo(request.priceBasisQuantity()) == 0))
        household.add(node.getMaterialCode());
      boolean belowPreparedRoot = roots.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
      boolean hasChildren = parents.contains(node.getNodeKey());
      if (!belowPreparedRoot && !hasChildren && node.getQtyPerTop() != null && node.getQtyPerTop().signum() != 0)
        unresolved.add(node.getMaterialCode());
    }
    household.addAll(unresolved);
    return new Scope(MonthlyPriceDocumentCodec.sha256(String.join("\n", canonical)), unresolved, household, true);
  }

  /** Pending MAKE/PACK rows may still carry the necessary raw/scrap material identities. */
  private void unresolvedComponents(PricePrepareItem row, Set<String> materials) {
    if (row.getInputSnapshotJson() == null || row.getInputSnapshotJson().isBlank()) return;
    var snapshot = codec.decode(codec.wrap(row.getInputSnapshotJson()), com.fasterxml.jackson.databind.JsonNode.class);
    for (var component : snapshot.path("components")) addMaterial(materials, component.path("materialCode").asText());
    for (var component : snapshot.path("rows")) {
      addMaterial(materials, component.path("childMaterialNo").asText());
      addMaterial(materials, component.path("scrapCode").asText());
    }
  }
  private static void addMaterial(Set<String> materials, String code) {
    if (code != null && !code.isBlank()) materials.add(code);
  }

  private static boolean sameNode(QuoteEffectiveBomNode node, MonthlyPriceRequest request) {
    var family = request.family();
    return family.materialCode().equals(node.getMaterialCode())
        && request.priceOrgCode().equals(node.getPriceOrgCode())
        && (node.getMaterialUnit() == null || family.unit().equals(node.getMaterialUnit()))
        && Objects.equals(Objects.toString(family.specification(), ""), Objects.toString(node.getMaterialSpec(), ""));
  }

  private static NonCuConditions conditions(OaForm form, String org) {
    if (!"220".equals(org) || form.getSus304Price() == null && form.getSus316lPrice() == null
        && !Boolean.TRUE.equals(form.getBoardTender())) return NonCuConditions.empty();
    return new NonCuConditions(new BoardTerms(form.getSus304Price(), form.getSus316lPrice(), Boolean.TRUE.equals(form.getBoardTender())));
  }
}
