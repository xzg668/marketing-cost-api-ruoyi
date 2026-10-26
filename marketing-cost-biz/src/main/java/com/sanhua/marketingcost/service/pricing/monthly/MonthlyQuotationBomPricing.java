package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.settlement.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Component;

/** Selects the pricing view of an existing effective BOM before ordinary settlement rules. */
@Component
public final class MonthlyQuotationBomPricing {
  public static final String HOUSEHOLD_ROW = "HOUSEHOLD_INQUIRY";
  public record View(MonthlyBomPricingScope.Scope scope, List<BomSettlementNode> visible,
      Map<String, String> units, Map<MonthlyPriceRequest, MonthlyMaterialPrices.Candidate> prices) {
    public View { visible = List.copyOf(visible); units = Map.copyOf(units); prices = Map.copyOf(prices); }
  }
  private final MonthlyQuotationPrices prices;
  private final QuotationPriceSessions sessions;
  private final MaterialMasterRawMapper materials;

  public MonthlyQuotationBomPricing(MonthlyQuotationPrices prices, QuotationPriceSessions sessions,
      MaterialMasterRawMapper materials) {
    this.prices = prices; this.sessions = sessions; this.materials = materials;
  }

  public View select(String oaNo, long itemId, String businessUnit, String month, String organization,
      LocalDateTime asOf, List<BomSettlementNode> original, Map<String, String> frozenUnits) {
    var session = sessions.open(new QuotationPriceSessions.Request(oaNo, businessUnit, original.getFirst().topProductCode(), month,
        asOf, new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(itemId).forOrganization(organization),
        new QuotationPriceSession.Scope()));
    var units = new HashMap<String, String>();
    frozenUnits.forEach((path, unit) -> { if (unit != null && !unit.isBlank()) units.put(MonthlyBomPricingScope.pathKey(path), unit); });
    var missing = new HashMap<String, Set<String>>();
    original.stream().filter(node -> !units.containsKey(MonthlyBomPricingScope.pathKey(node.path())))
        .forEach(node -> missing.computeIfAbsent(node.priceOrgCode(), ignored -> new HashSet<>()).add(node.materialCode()));
    var byMaterial = new HashMap<String, String>();
    missing.forEach((org, codes) -> {
      for (var master : materials.selectByLatestBatchAndCodes(codes, null, MaterialOrganization.fromPriceOrgCode(org).getCode())) {
        if (master.getUnit() == null || master.getUnit().isBlank()
            || byMaterial.putIfAbsent(org + "|" + master.getMaterialCode(), master.getUnit()) != null)
          throw new IllegalArgumentException("BOM 月价范围缺少唯一计价单位：" + master.getMaterialCode());
      }
    });
    var inputs = new LinkedHashMap<String, MonthlyQuotationPrices.Item>();
    for (var node : original) {
      String path = MonthlyBomPricingScope.pathKey(node.path());
      String unit = units.getOrDefault(path, byMaterial.get(node.priceOrgCode() + "|" + node.materialCode()));
      if (unit == null) throw new IllegalArgumentException("BOM 月价范围缺少物料计价单位：" + node.materialCode());
      units.put(path, unit);
      inputs.put(path, new MonthlyQuotationPrices.Item(node.materialCode(), node.priceOrgCode(), unit,
          node.materialSpec(), null, null, session.conditions(node.priceOrgCode()), node.qtyPerTop(), MonthlyQuotationPrices.Purpose.MATERIAL));
    }
    var pricingPaths = pricingPaths(original);
    var probes = prices.probeHousehold(session.context(), inputs.entrySet().stream()
        .filter(entry -> pricingPaths.contains(entry.getKey())).map(Map.Entry::getValue).toList());
    var problems = new LinkedHashMap<MonthlyPriceRequest, MonthlyMaterialPrices.Problem>();
    var nodes = new ArrayList<MonthlyBomPricingScope.Node>();
    for (var node : original) {
      var input = inputs.get(MonthlyBomPricingScope.pathKey(node.path()));
      var found = probes.values().get(input);
      var request = found == null ? MonthlyQuotationPrices.requests(session.context(), input).getLast() : found.request();
      if (probes.problems().containsKey(input)) problems.put(request, probes.problems().get(input));
      nodes.add(new MonthlyBomPricingScope.Node(node.path(), node.materialCode(), node.level() == 0 ? null : node.parentCode(), request, node.qtyPerTop()));
    }
    var scope = MonthlyBomPricingScope.select(nodes, new MonthlyMaterialPrices.Candidates(probes.candidates(), problems), pricingPaths);
    var ordinary = new HashSet<String>(); scope.ordinary().forEach(node -> ordinary.add(MonthlyBomPricingScope.pathKey(node.path())));
    var household = new HashSet<String>(); scope.household().forEach(use -> household.add(MonthlyBomPricingScope.pathKey(use.path())));
    var visible = new ArrayList<BomSettlementNode>();
    for (var node : original) {
      String path = MonthlyBomPricingScope.pathKey(node.path());
      if (household.contains(path)) visible.add(purchasedWhole(node));
      else if (ordinary.contains(path)) visible.add(node);
    }
    return new View(scope, visible, units, probes.candidates());
  }

  private static Set<String> pricingPaths(List<BomSettlementNode> nodes) {
    var packagePaths = nodes.stream().filter(BomSettlementRowBuildEngine::isPackageComponentParent)
        .map(node -> MonthlyBomPricingScope.pathKey(node.path())).collect(java.util.stream.Collectors.toSet());
    var wholeChildPaths = new HashSet<String>();
    for (var node : nodes) {
      String path = MonthlyBomPricingScope.pathKey(node.path());
      int slash = path.lastIndexOf('/');
      if (slash > 0 && packagePaths.contains(path.substring(0, slash))) wholeChildPaths.add(path);
    }
    var paths = new HashSet<String>();
    for (var node : nodes) {
      String path = MonthlyBomPricingScope.pathKey(node.path());
      if (wholeChildPaths.stream().noneMatch(boundary -> path.startsWith(boundary + "/"))) paths.add(path);
    }
    return Set.copyOf(paths);
  }

  public void stamp(BomSettlementRowBuildResult built, View view) {
    var household = new HashSet<String>(); view.scope().household().forEach(use -> household.add(MonthlyBomPricingScope.pathKey(use.path())));
    for (var row : built.costingRows()) {
      String path = MonthlyBomPricingScope.pathKey(row.getPath());
      if (view.units().containsKey(path)) row.setUnit(view.units().get(path));
      if (household.contains(path)) { row.setSettlementRowType(HOUSEHOLD_ROW); row.setSubtreeCostRequired(0); }
    }
  }

  private static BomSettlementNode purchasedWhole(BomSettlementNode node) {
    return new BomSettlementNode(node.sourceNodeId(), node.topProductCode(), node.parentCode(), node.materialCode(),
        node.level(), node.path(), node.qtyPerParent(), node.qtyPerTop(), node.materialName(), node.materialSpec(),
        "采购件", "采购件", node.costElementCode(), null, null, null, node.bomPurpose(), node.bomVersion(),
        node.u9IsCostFlag(), 1, node.effectiveFrom(), node.effectiveTo(), node.rawVersionEffectiveFrom(),
        node.priceOrgCode(), node.materialOrganizationCode(), node.businessUnitType(), node.sourceRef());
  }
}
