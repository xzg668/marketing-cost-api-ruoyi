package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.settlement.*;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Component;

/** MPP-06 application seam; quotation entry points switch to it together in MPP-12. */
@Component
public final class MonthlyBomPricePreparation {
  /** The existing caller decides whether a base material is priced through the scrap route. */
  public enum PricePurpose { MATERIAL, SCRAP }

  /** Explicit conversion supplied by the existing unit rules; no inferred conversion or rounding. */
  public record PriceInput(MonthlyPriceRequest request, BigDecimal pricingUnitFactor, PricePurpose purpose) {
    public PriceInput {
      Objects.requireNonNull(request);
      Objects.requireNonNull(purpose);
      if (pricingUnitFactor == null || pricingUnitFactor.signum() <= 0) {
        throw invalid("An explicit positive pricing-unit conversion is required");
      }
    }
  }

  /** Existing ordinary/MAKE/PACK paths return the base prices they actually consume. */
  @FunctionalInterface
  public interface OrdinaryUses {
    List<Use> resolve(BomSettlementBuildRequest visibleBom, BomSettlementRowBuildResult ordinaryRows);
  }

  public record Preview(MonthlyBomPricingScope.Scope scope, BomSettlementRowBuildResult ordinaryRows,
      MonthlyAdoptionPlan plan) {}
  public record Prepared(MonthlyBomPricingScope.Scope scope, BomSettlementRowBuildResult ordinaryRows,
      AdoptionResult adoption) {}

  private final MonthlyMaterialPrices prices;
  private final HouseholdMonthlyPriceSource household;
  private final BomSettlementRowBuildEngine ordinary;

  public MonthlyBomPricePreparation(MonthlyMaterialPrices prices, HouseholdMonthlyPriceSource household,
      BomSettlementRowBuildEngine ordinary) {
    this.prices = Objects.requireNonNull(prices);
    this.household = Objects.requireNonNull(household);
    this.ordinary = Objects.requireNonNull(ordinary);
  }

  /** This operation can be used by calculate/check/preview; it never adopts a price. */
  public Preview preview(Context context, BomSettlementBuildRequest bom,
      Map<String, PriceInput> inputs, OrdinaryUses ordinaryUses) {
    var priceInputs = normalizedInputs(inputs);
    var nodes = bind(context, bom, priceInputs);
    var requests = new LinkedHashSet<MonthlyPriceRequest>();
    nodes.stream().filter(node -> node.quantityPerTop().signum() > 0)
        .forEach(node -> requests.add(node.request()));
    var saved = prices.lookup(requests);
    var probes = new LinkedHashMap<MonthlyPriceRequest, Candidate>();
    saved.values().forEach((request, value) -> probes.put(request, new Candidate(value, null)));
    var householdRequests = new LinkedHashSet<MonthlyPriceRequest>();
    nodes.stream().filter(node -> priceInputs.get(MonthlyBomPricingScope.pathKey(node.path())).purpose() == PricePurpose.MATERIAL
        && saved.missing().contains(node.request())).forEach(node -> householdRequests.add(node.request()));
    var missing = household.read(context, householdRequests);
    probes.putAll(missing.values());
    var scope = MonthlyBomPricingScope.select(nodes, new Candidates(probes, missing.problems()));

    var visiblePaths = new HashSet<String>();
    scope.ordinary().forEach(node -> visiblePaths.add(MonthlyBomPricingScope.pathKey(node.path())));
    var visible = new BomSettlementBuildRequest(bom.oaNo(), bom.topProductCode(), bom.asOfDate(),
        bom.periodMonth(), bom.buildBatchId(), bom.builtAt(), bom.businessUnitType(), bom.bomPurpose(),
        bom.nodes().stream().filter(node -> visiblePaths.contains(MonthlyBomPricingScope.pathKey(node.path()))).toList(),
        bom.settlementRules(), bom.byproducts(), bom.scrapRefs(), bom.byproductRules());
    var rows = ordinary.build(visible);
    var ordinaryPriceUses = List.copyOf(ordinaryUses.resolve(visible, rows));
    validateOrdinaryUses(scope, visible, rows, priceInputs, ordinaryPriceUses);
    // Resolve only priced rows and their source dependencies, never ordinary sources under a cut.
    var candidates = resolveUsedPrices(context, scope.household(), ordinaryPriceUses, priceInputs);
    var plan = MonthlyBomPricingScope.adoption(scope, ordinaryPriceUses, candidates);
    return new Preview(scope, rows, plan);
  }

  /** Every retry starts from the unchanged effective BOM and reads committed monthly facts again. */
  public Prepared prepare(Context context, BomSettlementBuildRequest bom,
      Map<String, PriceInput> inputs, OrdinaryUses ordinaryUses) {
    var stableInputs = Map.copyOf(inputs);
    var attempt = new Preview[1];
    var adopted = new MonthlyAdoptionExecutor(prices).execute(context, budget -> {
      attempt[0] = preview(context, bom, stableInputs, ordinaryUses);
      return attempt[0].plan();
    });
    return new Prepared(attempt[0].scope(), attempt[0].ordinaryRows(), adopted);
  }

  private Candidates resolveUsedPrices(Context context, List<Use> householdUses, List<Use> ordinaryUses,
      Map<String, PriceInput> inputs) {
    var materials = new LinkedHashSet<MonthlyPriceRequest>();
    var scrap = new LinkedHashSet<MonthlyPriceRequest>();
    householdUses.forEach(use -> materials.add(use.request()));
    for (var use : ordinaryUses) {
      var input = inputs.get(MonthlyBomPricingScope.pathKey(use.path()));
      (input.purpose() == PricePurpose.SCRAP ? scrap : materials).add(use.request());
    }
    var values = new LinkedHashMap<MonthlyPriceRequest, Candidate>();
    var problems = new LinkedHashMap<MonthlyPriceRequest, Problem>();
    var batches = new ArrayList<Candidates>();
    if (!materials.isEmpty()) batches.add(prices.resolveCandidates(context, materials));
    if (!scrap.isEmpty()) batches.add(prices.resolveScrapCandidates(context, scrap));
    for (var batch : batches) {
      batch.values().forEach((request, candidate) -> {
        var previous = values.putIfAbsent(request, candidate);
        if (problems.containsKey(request) || previous != null && !previous.equals(candidate)) {
          throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,
              "Material and scrap batches observed different dependency facts");
        }
      });
      batch.problems().forEach((request, problem) -> {
        if (values.containsKey(request)) throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,
            "A price dependency changed between material and scrap batches");
        problems.put(request, problem);
      });
    }
    return new Candidates(values, problems);
  }

  private static void validateOrdinaryUses(MonthlyBomPricingScope.Scope scope,
      BomSettlementBuildRequest visible, BomSettlementRowBuildResult rows,
      Map<String, PriceInput> inputs, List<Use> uses) {
    var expected = new HashMap<String, Use>();
    var structuralPaths = new HashSet<String>();
    visible.nodes().stream().filter(MonthlyBomPricePreparation::isStructuralParent)
        .forEach(node -> structuralPaths.add(MonthlyBomPricingScope.pathKey(node.path())));
    var derivedParents = new HashSet<String>();
    var directRows = new HashSet<String>();
    for (var row : rows.costingRows()) {
      String path = MonthlyBomPricingScope.pathKey(row.getPath());
      if (Integer.valueOf(1).equals(row.getSubtreeCostRequired())
          || BomSettlementRowBuildEngine.ROW_TYPE_PACKAGE_PARENT.equals(row.getSettlementRowType())) {
        derivedParents.add(path);
      } else if (!structuralPaths.contains(path)) directRows.add(path);
    }
    // A MAKE/PACK result is quote-specific. Only the base prices consumed by its existing
    // calculation may be adopted; the structural parent is never an ordinary monthly price.
    for (var node : scope.ordinary()) {
      String path = MonthlyBomPricingScope.pathKey(node.path());
      if (!structuralPaths.contains(path) && (directRows.contains(path) || below(path, derivedParents))) {
        expected.put(path, new Use(node.path(), node.request(), node.quantityPerTop()));
      }
    }
    for (var row : rows.costingRows()) {
      String path = MonthlyBomPricingScope.pathKey(row.getPath());
      if (expected.containsKey(path) || !directRows.contains(path)) continue;
      var input = inputs.get(path);
      if (input != null && input.request().family().materialCode().equals(row.getMaterialCode())
          && input.request().priceOrgCode().equals(row.getPriceOrgCode())
          && row.getQtyPerTop() != null && row.getQtyPerTop().signum() != 0) {
        // Byproduct rows carry a cost sign; adoption records the positive physical price usage.
        expected.put(path, new Use(row.getPath(), input.request(),
            row.getQtyPerTop().abs().multiply(input.pricingUnitFactor())));
      }
    }
    for (var use : uses) {
      var basis = expected.get(MonthlyBomPricingScope.pathKey(use.path()));
      if (basis == null || !basis.request().equals(use.request())
          || basis.cumulativeQuantity().compareTo(use.cumulativeQuantity()) != 0) {
        throw invalid("Ordinary price use does not match the visible BOM/settlement row at " + use.path());
      }
    }
  }

  private static boolean isStructuralParent(BomSettlementNode node) {
    // Match the existing settlement engine: productionCategory supplies MANUFACTURE only.
    return matchesShape(node.shapeAttr(), QuoteMaterialShape.MANUFACTURE)
        || matchesShape(node.productionCategory(), QuoteMaterialShape.MANUFACTURE)
        || matchesShape(node.shapeAttr(), QuoteMaterialShape.VIRTUAL);
  }

  private static boolean matchesShape(String value, QuoteMaterialShape expected) {
    if (value == null || value.isBlank()) return false;
    try {
      return QuoteMaterialShape.fromU9(value) == expected;
    } catch (IllegalArgumentException otherCategory) {
      return false;
    }
  }

  private static boolean below(String path, Set<String> roots) {
    for (int slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/', slash - 1)) {
      if (roots.contains(path.substring(0, slash))) return true;
    }
    return false;
  }

  private static List<MonthlyBomPricingScope.Node> bind(Context context, BomSettlementBuildRequest bom,
      Map<String, PriceInput> inputs) {
    Objects.requireNonNull(bom);
    if (!context.oaNo().equals(bom.oaNo()) || !context.productCode().equals(bom.topProductCode())
        || !context.businessUnitType().equals(bom.businessUnitType())
        || !context.month().equals(YearMonth.parse(bom.periodMonth())) || bom.asOfDate() == null) {
      throw invalid("BOM does not belong to the current quotation/month");
    }
    var result = new ArrayList<MonthlyBomPricingScope.Node>();
    for (var node : bom.nodes()) {
      String path = MonthlyBomPricingScope.pathKey(node.path());
      var input = inputs.get(path);
      int depth = (int) path.chars().filter(character -> character == '/').count();
      if (input == null || !context.productCode().equals(node.topProductCode())
          || !context.businessUnitType().equals(node.businessUnitType())
          || !context.month().equals(input.request().family().month())
          || !context.businessUnitType().equals(input.request().family().businessUnitType())
          || !input.request().priceOrgCode().equals(node.priceOrgCode())
          || node.level() == null || node.level() != depth
          || depth == 0 && !context.productCode().equals(node.materialCode())) {
        throw invalid("BOM pricing identity differs at " + path);
      }
      if (node.qtyPerParent() == null || node.qtyPerParent().signum() < 0
          || node.qtyPerTop() == null || node.qtyPerTop().signum() < 0) {
        throw invalid("Invalid original BOM quantity at " + path);
      }
      if (node.effectiveFrom() != null && bom.asOfDate().isBefore(node.effectiveFrom())
          || node.effectiveTo() != null && bom.asOfDate().isAfter(node.effectiveTo())
          || node.bomPurpose() != null && !node.bomPurpose().isBlank() && !"主制造".equals(node.bomPurpose())) {
        throw invalid("Monthly scope requires the existing effective manufacturing BOM at " + path);
      }
      result.add(new MonthlyBomPricingScope.Node(node.path(), node.materialCode(), node.parentCode(),
          input.request(), node.qtyPerTop().multiply(input.pricingUnitFactor())));
    }
    MonthlyBomPricingScope.validate(result);
    var parentPaths = new HashSet<String>();
    result.forEach(node -> {
      String path = MonthlyBomPricingScope.pathKey(node.path());
      int slash = path.lastIndexOf('/');
      if (slash >= 0) parentPaths.add(path.substring(0, slash));
    });
    for (var node : bom.nodes()) {
      if (node.isLeaf() == null || node.isLeaf() != 0 && node.isLeaf() != 1
          || node.leaf() && parentPaths.contains(MonthlyBomPricingScope.pathKey(node.path()))) {
        throw invalid("Original BOM leaf identity contradicts its children at " + node.path());
      }
    }
    return List.copyOf(result);
  }

  private static MonthlyPriceProblem invalid(String message) {
    return new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT, message);
  }

  private static Map<String, PriceInput> normalizedInputs(Map<String, PriceInput> inputs) {
    var result = new HashMap<String, PriceInput>();
    inputs.forEach((path, input) -> {
      if (input == null || result.putIfAbsent(MonthlyBomPricingScope.pathKey(path), input) != null) {
        throw invalid("Missing or duplicate price input at " + path);
      }
    });
    return Map.copyOf(result);
  }
}
