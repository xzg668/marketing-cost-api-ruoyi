package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.util.*;

/** Pure path selection over an existing effective BOM. It neither builds a BOM nor prices MAKE/PACK. */
public final class MonthlyBomPricingScope {
  private MonthlyBomPricingScope() {}

  /** Quantities are supplied by the existing BOM/unit conversion path, before any display rounding. */
  public record Node(String path, String materialCode, String parentMaterialCode,
      MonthlyPriceRequest request, BigDecimal quantityPerTop) {
    public Node {
      if (materialCode == null || materialCode.isBlank()) throw invalid("BOM material identity is required");
      // A quotation-only structural root has no material price identity or unit.
      // Priced children always retain their complete request.
      if (request == null && (parent(pathKey(path)) != null || parentMaterialCode != null)) {
        throw invalid("Only a structural BOM root can omit its price request");
      }
      if (request != null && !request.family().materialCode().equals(materialCode)) {
        throw invalid("BOM material and price identity differ at " + path);
      }
      pathKey(path);
      if (quantityPerTop == null || quantityPerTop.signum() < 0) {
        throw invalid("BOM quantity must be present and nonnegative at " + path);
      }
      quantityPerTop = quantityPerTop.stripTrailingZeros();
    }
  }

  public record Scope(List<Node> original, List<Node> ordinary, List<Use> household,
      Set<String> suppressedPaths) {
    public Scope {
      original = List.copyOf(original);
      ordinary = List.copyOf(ordinary);
      household = List.copyOf(household);
      suppressedPaths = Set.copyOf(suppressedPaths);
    }
  }

  /** Validate the complete input before considering any household cut, including zero-quantity rows. */
  public static void validate(List<Node> nodes) {
    var byPath = new HashMap<String, Node>();
    MonthlyPriceFamilyKey first = null;
    int roots = 0;
    for (var node : nodes) {
      if (node == null) throw invalid("Null BOM node");
      String path = pathKey(node.path());
      if (byPath.putIfAbsent(path, node) != null) throw invalid("Duplicate BOM path: " + path);
      if (node.request() == null) continue;
      var family = node.request().family();
      if (first == null) first = family;
      if (!first.businessUnitType().equals(family.businessUnitType()) || !first.month().equals(family.month())) {
        throw invalid("Mixed business unit or accounting month in BOM");
      }
    }
    for (var node : nodes) {
      String parentPath = parent(pathKey(node.path()));
      if (parentPath == null) {
        roots++;
        if (node.parentMaterialCode() != null && !node.parentMaterialCode().isBlank()) {
          throw invalid("BOM root has a parent material: " + node.path());
        }
      } else {
        var parent = byPath.get(parentPath);
        if (parent == null || !parent.materialCode().equals(node.parentMaterialCode())) {
          throw invalid("Broken BOM parent identity at " + node.path());
        }
        if (parent.quantityPerTop().signum() == 0 && node.quantityPerTop().signum() > 0) {
          throw invalid("Positive child quantity below zero-quantity parent at " + node.path());
        }
      }
    }
    if (!nodes.isEmpty() && roots != 1) throw invalid("An effective BOM must have one root");
  }

  /** Saved NORMAL facts take precedence; candidates contain only missing household probes here. */
  public static Scope select(List<Node> nodes, Candidates probes) {
    validate(nodes);
    return select(nodes, probes, nodes.stream().map(node -> pathKey(node.path())).collect(java.util.stream.Collectors.toSet()));
  }

  /** Price only original paths reached by the existing calculator's input boundaries. */
  public static Scope select(List<Node> nodes, Candidates probes, Set<String> pricingPaths) {
    validate(nodes);
    var ordered = nodes.stream().sorted(Comparator.comparing(node -> pathKey(node.path()))).toList();
    var covered = new HashSet<String>();
    var suppressed = new LinkedHashSet<String>();
    var ordinary = new ArrayList<Node>();
    var household = new ArrayList<Use>();
    for (var node : ordered) {
      String path = pathKey(node.path());
      if (covered.contains(parent(path))) {
        covered.add(path);
        suppressed.add(node.path());
        continue;
      }
      // Zero consumption can explain the original tree but cannot adopt a monthly price.
      if (node.quantityPerTop().signum() == 0) continue;
      if (node.request() == null) {
        ordinary.add(node);
        continue;
      }
      if (!pricingPaths.contains(path)) continue;
      var problem = probes.problems().get(node.request());
      if (problem != null) throw new MonthlyPriceProblem(problem.reason(), node.path() + ": " + problem.message());
      var candidate = probes.values().get(node.request());
      if (candidate != null && !candidate.selection().applicability().matches(node.request())) {
        throw invalid("Probe does not apply to BOM request at " + node.path());
      }
      if (candidate != null && candidate.selection().fact().type() == PricingType.HOUSEHOLD_INQUIRY) {
        household.add(new Use(node.path(), node.request(), node.quantityPerTop()));
        covered.add(path);
      } else ordinary.add(node);
    }
    return new Scope(nodes, ordinary, household, suppressed);
  }

  /** Only actual ordinary uses and their reachable price dependencies enter adoption. */
  public static MonthlyAdoptionPlan adoption(Scope scope, List<Use> ordinaryUses, Candidates prices) {
    var householdPaths = new HashSet<String>();
    scope.household().forEach(use -> householdPaths.add(pathKey(use.path())));
    var suppressed = new HashSet<String>();
    scope.suppressedPaths().forEach(path -> suppressed.add(pathKey(path)));
    var paths = new HashSet<String>();
    var uses = new ArrayList<>(scope.household());
    uses.addAll(ordinaryUses);
    for (var use : uses) {
      String path = pathKey(use.path());
      if (!paths.add(path) || suppressed.contains(path) || below(path, householdPaths)) {
        throw invalid("Ordinary pricing reused a household-covered path: " + path);
      }
      var selected = require(prices, use.request(), path);
      if (householdPaths.contains(path) != (selected.fact().type() == PricingType.HOUSEHOLD_INQUIRY)) {
        throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,
            "Monthly pricing type changed while planning " + path);
      }
    }
    var selected = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    var pending = new ArrayDeque<MonthlyPriceRequest>();
    uses.forEach(use -> pending.add(use.request()));
    while (!pending.isEmpty()) {
      var request = pending.removeFirst();
      if (selected.containsKey(request)) continue;
      var value = require(prices, request, "price dependency");
      selected.put(request, value);
      pending.addAll(value.fact().dependencies());
    }
    var ordinaryRequests = new LinkedHashSet<MonthlyPriceRequest>();
    scope.ordinary().stream().map(Node::request).filter(Objects::nonNull).forEach(ordinaryRequests::add);
    return new MonthlyAdoptionPlan(selected, uses, scope.suppressedPaths(), ordinaryRequests);
  }

  /** Aggregate by the adopted fact identity, retaining each original Use for audit. No rounding here. */
  public static Map<PriceIdentity, BigDecimal> quantities(MonthlyAdoptionPlan plan) {
    var result = new LinkedHashMap<PriceIdentity, BigDecimal>();
    for (var use : plan.uses()) {
      var selection = Objects.requireNonNull(plan.selections().get(use.request()), "missing use price");
      var identity = new PriceIdentity(use.request().family(), selection.applicability());
      result.merge(identity, use.cumulativeQuantity(), BigDecimal::add);
    }
    return Map.copyOf(result);
  }

  public record PriceIdentity(MonthlyPriceFamilyKey family, MonthlyPriceApplicability applicability) {}

  private static Selection require(Candidates prices, MonthlyPriceRequest request, String path) {
    var problem = prices.problems().get(request);
    if (problem != null) throw new MonthlyPriceProblem(problem.reason(), path + ": " + problem.message());
    var candidate = prices.values().get(request);
    if (candidate == null) throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.PERSISTENCE_INCOMPLETE,
        "Missing candidate for " + path + " / " + request.family().materialCode());
    return candidate.selection();
  }

  public static String pathKey(String path) {
    if (path == null || path.isBlank()) throw invalid("BOM path is required");
    String key = path.trim();
    if (key.startsWith("/")) key = key.substring(1);
    if (key.endsWith("/")) key = key.substring(0, key.length() - 1);
    if (key.isEmpty() || Arrays.stream(key.split("/", -1)).anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
      throw invalid("Invalid BOM path: " + path);
    }
    return key;
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? null : path.substring(0, slash);
  }

  private static boolean below(String path, Set<String> roots) {
    for (String ancestor = parent(path); ancestor != null; ancestor = parent(ancestor)) {
      if (roots.contains(ancestor)) return true;
    }
    return false;
  }

  private static MonthlyPriceProblem invalid(String message) {
    return new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT, message);
  }
}
