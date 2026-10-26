package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.formula.registry.PreparedFormulaInputs;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** Resolves captured formula mappings through the same monthly module as all other consumers. */
public final class MonthlyFormulaScrapDependencies {
  public record Input(PreparedFormulaInputs.Scrap scrap, Set<MonthlyPriceRequest> dependencies,
      MonthlyPriceApplicability availability, Map<MonthlyPriceRequest, Candidate> resolvedDependencies) {
    public Input {
      dependencies = Set.copyOf(dependencies);
      resolvedDependencies = Map.copyOf(resolvedDependencies);
    }
  }

  public record Result(Map<MonthlyPriceRequest, Input> values, Map<MonthlyPriceRequest, Problem> problems) {
    public Result { values = Map.copyOf(values); problems = Map.copyOf(problems); }
  }

  private record ChildBasis(int snapshotSchema, String sourceKind, MonthlyPriceFamilyKey family,
      MonthlyPriceApplicability applicability, BigDecimal price, String sourceEvidenceSha256) {}
  private record Pending(MonthlyPriceRequest child, List<MaterialScrapRef> references) {}
  private record Ready(PreparedFormulaInputs.ScrapPricePerKgExclTax price,
      MonthlyPriceApplicability availability, Map<MonthlyPriceRequest, Candidate> closure, Problem problem) {}
  private final MonthlyPriceDocumentCodec codec;

  public MonthlyFormulaScrapDependencies(MonthlyPriceDocumentCodec codec) {
    this.codec = Objects.requireNonNull(codec);
  }

  /** The caller intersects availability with its own coverage and returns only successful parents' closures. */
  public Result resolve(Context context, Map<MonthlyPriceRequest, List<MaterialScrapRef>> references,
      Function<Set<MonthlyPriceRequest>, Candidates> dependencies) {
    var pending = new LinkedHashMap<MonthlyPriceRequest, Pending>();
    var problems = new LinkedHashMap<MonthlyPriceRequest, Problem>();
    for (var entry : references.entrySet()) {
      var parent = entry.getKey();
      scope(context, parent);
      try {
        var rows = new PreparedFormulaInputs.Scrap(
            Map.of(parent.family().materialCode(), entry.getValue()), Map.of())
            .references().get(parent.family().materialCode());
        if (rows.isEmpty()) {
          problems.put(parent, new Problem(Failure.WAITING, "等待有效废料映射：" + parent.family().materialCode()));
          continue;
        }
        var first = rows.getFirst();
        BigDecimal ratio = first.getRatio() == null ? BigDecimal.ONE : first.getRatio();
        if (first.getScrapCode() == null || first.getScrapCode().isBlank() || ratio.signum() < 0)
          throw new IllegalArgumentException("废料映射缺料号或抵减比例无效");
        for (var row : rows) {
          var value = row.getRatio() == null ? BigDecimal.ONE : row.getRatio();
          if (!first.getScrapCode().equals(row.getScrapCode()) || ratio.compareTo(value) != 0)
            throw new IllegalArgumentException("公式废料变量存在多个有效映射");
        }
        var family = new MonthlyPriceFamilyKey(context.month(), context.businessUnitType(),
            first.getScrapCode(), null, "KG", "CNY", TaxBasis.EXCLUDED, null);
        // This consumer never passes an ordinary BOM quantity into scrap price selection.
        var child = new MonthlyPriceRequest(family, parent.priceOrgCode(), parent.conditions(), BigDecimal.ONE);
        pending.put(parent, new Pending(child, rows));
      } catch (IllegalArgumentException | NullPointerException invalid) {
        problems.put(parent, new Problem(Failure.INVALID_INPUT, "公式废料映射无效：" + invalid.getMessage()));
      }
    }
    if (pending.isEmpty()) return new Result(Map.of(), problems);
    var requests = new LinkedHashSet<MonthlyPriceRequest>();
    pending.values().forEach(value -> requests.add(value.child()));
    var resolved = dependencies.apply(Set.copyOf(requests));
    validateClosure(context, requests, resolved);
    var preparedChildren = new HashMap<MonthlyPriceRequest, Ready>();
    var values = new LinkedHashMap<MonthlyPriceRequest, Input>();
    for (var entry : pending.entrySet()) {
      var parent = entry.getKey();
      var item = entry.getValue();
      // Type validation, projection, digest and encoding run once per distinct dependency, not per parent.
      var ready = preparedChildren.computeIfAbsent(item.child(), child -> prepareChild(child, resolved));
      if (ready.problem() != null) {
        problems.put(parent, ready.problem());
        continue;
      }
      var scrap = new PreparedFormulaInputs.Scrap(
          Map.of(parent.family().materialCode(), item.references()),
          Map.of(item.child().family().materialCode(), ready.price()));
      values.put(parent, new Input(scrap, Set.of(item.child()), ready.availability(), ready.closure()));
    }
    return new Result(values, problems);
  }

  private Ready prepareChild(MonthlyPriceRequest root, Candidates resolved) {
    var closure = new LinkedHashMap<MonthlyPriceRequest, Candidate>();
    var visited = new HashSet<MonthlyPriceRequest>();
    var queue = new ArrayDeque<MonthlyPriceRequest>();
    queue.add(root);
    Problem blocker = null;
    while (!queue.isEmpty()) {
      var request = queue.remove();
      if (!visited.add(request)) continue;
      var issue = resolved.problems().get(request);
      if (issue != null) {
        if (blocker == null) blocker = dependencyProblem(issue);
        continue;
      }
      var candidate = resolved.values().get(request);
      closure.put(request, candidate);
      queue.addAll(candidate.selection().fact().dependencies());
    }
    if (blocker != null) return new Ready(null, null, Map.of(), blocker);
    var selected = closure.get(root).selection();
    if (selected.fact().type() != PricingType.NORMAL
        || selected.monthlyPriceId() != null && selected.monthlyPriceId() <= 0)
      return new Ready(null, null, Map.of(), new Problem(Failure.INVALID_INPUT,
          "已采用废料依赖的计价类型或保存身份不支持普通公式"));
    // Existing adopted facts retain priority when a source was added or changed later.
    if (selected.monthlyPriceId() == null) {
      var header = codec.decode(codec.wrap(selected.fact().evidence().json()), Map.class);
      if (!"SCRAP_PRICE".equals(header.get("sourceKind")))
        return new Ready(null, null, Map.of(), new Problem(Failure.WAITING,
            "废料来源尚未有效，不能采用其他当前来源兜底"));
    }
    var fragments = selected.applicability().fragments().stream()
        .filter(fragment -> fragment.quantities().contains(BigDecimal.ONE))
        .map(fragment -> new MonthlyPriceCoverage(
            fragment.organizations(), QuantityInterval.all(), fragment.conditions())).toList();
    var availability = new MonthlyPriceApplicability(fragments);
    // Adoption persists the real child IDs and complete immutable facts in the dependency graph.
    var basis = new ChildBasis(1, "MONTHLY_FORMULA_SCRAP_INPUT", root.family(),
        selected.applicability(), selected.fact().price(), selected.fact().evidence().sha256());
    var price = new PreparedFormulaInputs.ScrapPricePerKgExclTax(selected.fact().price(),
        SourceEvidence.ofJson(codec.unwrap(codec.encode(basis))));
    return new Ready(price, availability, Map.copyOf(closure), null);
  }

  private static void validateClosure(Context context, Set<MonthlyPriceRequest> roots, Candidates result) {
    var reachable = new HashSet<MonthlyPriceRequest>();
    var queue = new ArrayDeque<>(roots);
    while (!queue.isEmpty()) {
      var request = queue.remove();
      if (!reachable.add(request)) continue;
      scope(context, request);
      var candidate = result.values().get(request);
      if (candidate == null) {
        if (!result.problems().containsKey(request))
          throw new MonthlyPriceProblem(Failure.INVALID_INPUT, "Monthly callback omitted a required dependency");
      } else {
        if (!candidate.selection().applicability().matches(request))
          throw new MonthlyPriceProblem(Failure.INVALID_INPUT, "Monthly callback returned invalid dependency coverage");
        queue.addAll(candidate.selection().fact().dependencies());
      }
    }
    var returned = new HashSet<>(result.values().keySet());
    returned.addAll(result.problems().keySet());
    if (!returned.equals(reachable))
      throw new MonthlyPriceProblem(Failure.INVALID_INPUT, "Monthly callback returned unrelated dependency records");
  }

  private static Problem dependencyProblem(Problem issue) {
    var reason = issue.reason() == Failure.MISSING ? Failure.WAITING : issue.reason();
    if (reason != Failure.WAITING && reason != Failure.INVALID_INPUT)
      throw new MonthlyPriceProblem(reason, issue.message());
    return new Problem(reason, "公式废料依赖未就绪：" + issue.message());
  }

  private static void scope(Context context, MonthlyPriceRequest request) {
    if (!context.businessUnitType().equals(request.family().businessUnitType())
        || !context.month().equals(request.family().month()))
      throw new MonthlyPriceProblem(Failure.INVALID_INPUT, "Formula dependency scope differs from parent request");
  }
}
