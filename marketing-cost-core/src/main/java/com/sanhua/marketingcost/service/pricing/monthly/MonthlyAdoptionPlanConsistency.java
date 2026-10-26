package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PricingType;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PriceFact;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Selection;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Use;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Pure comparison of a candidate plan and the facts selected inside adoption. */
public final class MonthlyAdoptionPlanConsistency {
  private MonthlyAdoptionPlanConsistency() {}

  public enum Outcome {
    CONSISTENT,
    REPLAN_REQUIRED,
    INVALID_PLAN
  }

  public enum Reason {
    TYPE_CHANGED,
    VALUE_CHANGED,
    EVIDENCE_CHANGED,
    COVERAGE_CHANGED,
    DEPENDENCY_CHANGED,
    DEPENDENCY_SET_CHANGED,
    DERIVED_PARENT_CHANGED,
    USES_CHANGED,
    SCOPE_CHANGED,
    INVALID_INPUT,
    ORPHAN_DEPENDENCY,
    MISSING_DEPENDENCY,
    CYCLE,
    SUPPRESSED_USE,
    PERSISTENCE_INCOMPLETE
  }

  public record Result(
      Outcome outcome, Map<MonthlyPriceRequest, Set<Reason>> conflicts) {
    public Result {
      Objects.requireNonNull(outcome, "outcome");
      var immutableReasons = new LinkedHashMap<MonthlyPriceRequest, Set<Reason>>();
      conflicts.forEach((request, reasons) -> immutableReasons.put(request, Set.copyOf(reasons)));
      conflicts = Map.copyOf(immutableReasons);
    }
  }

  public static Result verify(MonthlyAdoptionPlan planned, MonthlyAdoptionPlan resolved) {
    Map<MonthlyPriceRequest, Set<Reason>> invalidCandidate = validateCandidate(planned);
    if (!invalidCandidate.isEmpty()) return new Result(Outcome.INVALID_PLAN, invalidCandidate);
    var invalidSavedRows = validateSavedIdentities(planned, resolved);
    if (!invalidSavedRows.isEmpty()) return new Result(Outcome.INVALID_PLAN, invalidSavedRows);

    var savedIds = new HashMap<RowIdentity, Long>();
    resolved.selections().forEach((request, selection) ->
        savedIds.put(identity(request, selection), selection.monthlyPriceId()));

    Map<MonthlyPriceRequest, Set<Reason>> conflicts = new LinkedHashMap<>();
    planned.selections().forEach((request, selection) -> {
      var adopted = resolved.selections().get(request);
      if (adopted == null) return;
      var expected = selection.fact();
      var saved = adopted.fact();
      var reasons = EnumSet.noneOf(Reason.class);
      if (expected.type() != saved.type()) reasons.add(Reason.TYPE_CHANGED);
      if (!sameNumber(expected.price(), saved.price())
          || !sameNumber(expected.householdRate(), saved.householdRate())) {
        reasons.add(Reason.VALUE_CHANGED);
        if (!expected.dependencies().isEmpty() || !saved.dependencies().isEmpty()) {
          reasons.add(Reason.DERIVED_PARENT_CHANGED);
        }
      }
      if (!expected.evidence().equals(saved.evidence())) reasons.add(Reason.EVIDENCE_CHANGED);
      if (!selection.applicability().equals(adopted.applicability())) {
        reasons.add(Reason.COVERAGE_CHANGED);
      }
      if (!Objects.equals(dependencyIds(planned, expected, savedIds),
          dependencyIds(resolved, saved, savedIds))) reasons.add(Reason.DEPENDENCY_SET_CHANGED);
      if (!reasons.isEmpty()) conflicts.put(request, reasons);
    });
    if (!planned.ordinaryScopeRequests().equals(resolved.ordinaryScopeRequests())) {
      planned.ordinaryScopeRequests().forEach(request -> add(conflicts, request, Reason.SCOPE_CHANGED));
      resolved.ordinaryScopeRequests().forEach(request -> add(conflicts, request, Reason.SCOPE_CHANGED));
    }
    if (!usesByPath(planned).equals(usesByPath(resolved))
        || !normalizedPaths(planned.suppressedPaths()).equals(normalizedPaths(resolved.suppressedPaths()))) {
      planned.uses().forEach(use -> add(conflicts, use.request(), Reason.USES_CHANGED));
      resolved.uses().forEach(use -> add(conflicts, use.request(), Reason.USES_CHANGED));
    }
    propagateDependencyChanges(planned, conflicts);
    // A legitimate winner can invalidate the old tree; that requires a full replan, not repair.
    if (!conflicts.isEmpty()) return new Result(Outcome.REPLAN_REQUIRED, conflicts);

    planned.selections().keySet().forEach(request -> {
      var saved = resolved.selections().get(request);
      if (saved == null || saved.monthlyPriceId() == null || saved.monthlyPriceId() <= 0) {
        add(conflicts, request, Reason.PERSISTENCE_INCOMPLETE);
      }
    });
    resolved.selections().keySet().stream()
        .filter(request -> !planned.selections().containsKey(request))
        .forEach(request -> add(conflicts, request, Reason.PERSISTENCE_INCOMPLETE));
    if (conflicts.isEmpty()) conflicts.putAll(validateCandidate(resolved));
    return new Result(conflicts.isEmpty() ? Outcome.CONSISTENT : Outcome.INVALID_PLAN, conflicts);
  }

  private static Map<MonthlyPriceRequest, Set<Reason>> validateCandidate(MonthlyAdoptionPlan plan) {
    Map<MonthlyPriceRequest, Set<Reason>> errors = new LinkedHashMap<>();
    var householdCoverage = new HashMap<MonthlyPriceFamilyKey, List<MonthlyPriceApplicability>>();
    plan.selections().forEach((request, selection) -> {
      if (selection.fact().type() == PricingType.HOUSEHOLD_INQUIRY) {
        householdCoverage.computeIfAbsent(request.family(), ignored -> new ArrayList<>()).add(selection.applicability());
      }
    });
    for (var guard : plan.ordinaryScopeRequests()) {
      if (householdCoverage.getOrDefault(guard.family(), List.of()).stream().anyMatch(coverage -> coverage.matches(guard))) {
        add(errors, guard, Reason.INVALID_INPUT);
      }
    }
    var households = new HashSet<String>();
    var paths = new HashSet<String>();
    for (Use use : plan.uses()) {
      String path = pathKey(use.path());
      if (!paths.add(path)) add(errors, use.request(), Reason.INVALID_INPUT);
      var selected = plan.selections().get(use.request());
      if (selected == null) add(errors, use.request(), Reason.MISSING_DEPENDENCY);
      else if (selected.fact().type() == PricingType.HOUSEHOLD_INQUIRY) households.add(path);
    }
    var suppressed = normalizedPaths(plan.suppressedPaths());
    for (Use use : plan.uses()) {
      if (suppressed.contains(pathKey(use.path())) || hasAncestor(pathKey(use.path()), households)) {
        add(errors, use.request(), Reason.SUPPRESSED_USE);
      }
    }
    for (String path : suppressed) {
      if (!hasAncestor(path, households)) {
        add(errors, plan.uses().get(0).request(), Reason.INVALID_INPUT);
      }
    }
    plan.selections().forEach((request, selected) -> {
      if (!selected.applicability().matches(request)) add(errors, request, Reason.INVALID_INPUT);
      selected.fact().dependencies().stream()
          .filter(dependency -> !plan.selections().containsKey(dependency))
          .forEach(dependency -> add(errors, request, Reason.MISSING_DEPENDENCY));
    });

    // Reachability and cycle detection are iterative: neither depends on the JVM stack depth.
    var reachable = new HashSet<MonthlyPriceRequest>();
    var pending = new ArrayDeque<MonthlyPriceRequest>();
    plan.uses().forEach(use -> pending.add(use.request()));
    while (!pending.isEmpty()) {
      var request = pending.removeFirst();
      if (!reachable.add(request)) continue;
      var selected = plan.selections().get(request);
      if (selected != null) pending.addAll(selected.fact().dependencies());
    }
    plan.selections().keySet().stream().filter(request -> !reachable.contains(request))
        .forEach(request -> add(errors, request, Reason.ORPHAN_DEPENDENCY));

    var parents = parentsByDependency(plan);
    var remaining = new HashMap<MonthlyPriceRequest, Integer>();
    plan.selections().forEach((request, selected) -> {
      int count = (int) selected.fact().dependencies().stream()
          .filter(plan.selections()::containsKey).count();
      remaining.put(request, count);
      if (count == 0) pending.add(request);
    });
    while (!pending.isEmpty()) {
      var dependency = pending.removeFirst();
      for (var parent : parents.getOrDefault(dependency, List.of())) {
        int count = remaining.computeIfPresent(parent, (ignored, previous) -> previous - 1);
        if (count == 0) pending.add(parent);
      }
    }
    remaining.forEach((request, count) -> {
      if (count > 0) add(errors, request, Reason.CYCLE);
    });
    if (errors.isEmpty()) validateCandidateRows(plan, errors);
    return errors;
  }

  private record RowIdentity(
      MonthlyPriceFamilyKey family, MonthlyPriceApplicability applicability) {}

  private record ScalarFact(
      PricingType type, BigDecimal price, BigDecimal householdRate, SourceEvidence evidence) {
    private static ScalarFact of(PriceFact fact) {
      return new ScalarFact(fact.type(), fact.price(), fact.householdRate(), fact.evidence());
    }
  }

  private record CandidateBasis(ScalarFact value, Set<RowIdentity> dependencies) {}

  private record RowOwner(RowIdentity identity, List<MonthlyPriceRequest> requests) {}

  private record IndexedCoverage(RowOwner row, MonthlyPriceCoverage coverage) {}

  private static RowIdentity identity(MonthlyPriceRequest request, Selection selection) {
    return new RowIdentity(request.family(), selection.applicability());
  }

  private static void validateCandidateRows(
      MonthlyAdoptionPlan plan, Map<MonthlyPriceRequest, Set<Reason>> errors) {
    var requestsByRow = new LinkedHashMap<RowIdentity, List<MonthlyPriceRequest>>();
    var facts = new HashMap<RowIdentity, CandidateBasis>();
    for (var entry : plan.selections().entrySet()) {
      var request = entry.getKey();
      var selection = entry.getValue();
      var row = identity(request, selection);
      var dependencies = new HashSet<RowIdentity>();
      selection.fact().dependencies().forEach(dependency ->
          dependencies.add(identity(dependency, plan.selections().get(dependency))));
      var basis = new CandidateBasis(ScalarFact.of(selection.fact()), Set.copyOf(dependencies));
      var aliases = requestsByRow.computeIfAbsent(row, ignored -> new ArrayList<>());
      aliases.add(request);
      var first = facts.putIfAbsent(row, basis);
      if (first != null && !first.equals(basis)) {
        aliases.forEach(alias -> add(errors, alias, Reason.INVALID_INPUT));
      }
    }
    if (!errors.isEmpty()) return;
    var rows = new ArrayList<RowOwner>();
    requestsByRow.forEach((identity, requests) -> rows.add(new RowOwner(identity, List.copyOf(requests))));
    validateNonOverlappingRows(rows, errors, Reason.INVALID_INPUT);
  }

  private static Map<MonthlyPriceRequest, Set<Reason>> validateSavedIdentities(
      MonthlyAdoptionPlan planned, MonthlyAdoptionPlan resolved) {
    Map<MonthlyPriceRequest, Set<Reason>> errors = new LinkedHashMap<>();
    var requestsById = new LinkedHashMap<Long, List<MonthlyPriceRequest>>();
    var identityById = new HashMap<Long, RowIdentity>();
    var valueById = new HashMap<Long, ScalarFact>();
    var dependenciesById = new HashMap<Long, Set<Long>>();
    for (var entry : resolved.selections().entrySet()) {
      var request = entry.getKey();
      var selection = entry.getValue();
      Long id = selection.monthlyPriceId();
      if (id == null || id <= 0) {
        add(errors, request, Reason.PERSISTENCE_INCOMPLETE);
        continue;
      }
      var previous = planned.selections().get(request);
      if (previous != null && previous.monthlyPriceId() != null
          && previous.monthlyPriceId() > 0 && !previous.monthlyPriceId().equals(id)) {
        add(errors, request, Reason.PERSISTENCE_INCOMPLETE);
      }
      var dependentIds = new HashSet<Long>();
      for (var dependency : selection.fact().dependencies()) {
        var dependent = resolved.selections().get(dependency);
        if (dependent == null || dependent.monthlyPriceId() == null || dependent.monthlyPriceId() <= 0) {
          add(errors, request, Reason.PERSISTENCE_INCOMPLETE);
        } else dependentIds.add(dependent.monthlyPriceId());
      }
      var aliases = requestsById.computeIfAbsent(id, ignored -> new ArrayList<>());
      aliases.add(request);
      var row = identity(request, selection);
      var firstRow = identityById.putIfAbsent(id, row);
      var value = ScalarFact.of(selection.fact());
      var firstValue = valueById.putIfAbsent(id, value);
      var firstDependencies = dependenciesById.putIfAbsent(id, Set.copyOf(dependentIds));
      if (firstRow != null && (!firstRow.equals(row) || !firstValue.equals(value)
          || !firstDependencies.equals(dependentIds))) {
        aliases.forEach(alias -> add(errors, alias, Reason.PERSISTENCE_INCOMPLETE));
      }
    }
    if (!errors.isEmpty()) return errors;
    var rows = new ArrayList<RowOwner>();
    requestsById.forEach((id, requests) ->
        rows.add(new RowOwner(identityById.get(id), List.copyOf(requests))));
    validateNonOverlappingRows(rows, errors, Reason.PERSISTENCE_INCOMPLETE);
    return errors;
  }

  private static Set<Long> dependencyIds(MonthlyAdoptionPlan plan, PriceFact fact,
      Map<RowIdentity, Long> savedIds) {
    var result = new HashSet<Long>();
    for (var dependency : fact.dependencies()) {
      var selected = plan.selections().get(dependency);
      if (selected == null) return null;
      Long id = savedIds.get(identity(dependency, selected));
      if (id == null) return null;
      result.add(id);
    }
    return result;
  }

  /** Sweep quantity intervals per family/organization; exact conditions use a hash index. */
  private static void validateNonOverlappingRows(List<RowOwner> rows,
      Map<MonthlyPriceRequest, Set<Reason>> errors, Reason reason) {
    var groups = new HashMap<MonthlyPriceFamilyKey, Map<String, List<IndexedCoverage>>>();
    for (var row : rows) {
      var organizations = groups.computeIfAbsent(row.identity().family(), ignored -> new HashMap<>());
      for (var fragment : row.identity().applicability().fragments()) {
        for (String organization : fragment.organizations()) {
          organizations.computeIfAbsent(organization, ignored -> new ArrayList<>())
              .add(new IndexedCoverage(row, fragment));
        }
      }
    }
    Comparator<IndexedCoverage> lowerOrder = Comparator.comparing(
        (IndexedCoverage row) -> row.coverage().quantities().lower(),
        Comparator.nullsFirst(BigDecimal::compareTo))
        .thenComparing(row -> !row.coverage().quantities().lowerInclusive());
    Comparator<IndexedCoverage> upperOrder = Comparator.comparing(
        (IndexedCoverage row) -> row.coverage().quantities().upper(),
        Comparator.nullsLast(BigDecimal::compareTo))
        .thenComparing(row -> row.coverage().quantities().upperInclusive());
    for (var organizations : groups.values()) {
      for (var fragments : organizations.values()) {
        fragments.sort(lowerOrder);
        var ending = new PriorityQueue<>(upperOrder);
        var exact = new HashMap<NonCuConditions, IndexedCoverage>();
        var broad = new HashSet<IndexedCoverage>();
        for (var current : fragments) {
          while (!ending.isEmpty() && endsBefore(ending.peek().coverage().quantities(),
              current.coverage().quantities())) {
            var expired = ending.remove();
            if (expired.coverage().conditions().mode() == ConditionDomain.Mode.EXACT) {
              expired.coverage().conditions().values().forEach(value -> exact.remove(value, expired));
            } else broad.remove(expired);
          }
          var domain = current.coverage().conditions();
          if (domain.mode() == ConditionDomain.Mode.EXACT) {
            for (var condition : domain.values()) {
              var previous = exact.get(condition);
              if (conflictingRows(current, previous, errors, reason)) return;
              for (var previousBroad : broad) {
                if (previousBroad.coverage().conditions().matches(condition)
                    && conflictingRows(current, previousBroad, errors, reason)) return;
              }
            }
            domain.values().forEach(value -> exact.put(value, current));
          } else {
            for (var previous : broad) if (conflictingRows(current, previous, errors, reason)) return;
            for (var previous : exact.entrySet()) {
              if (domain.matches(previous.getKey())
                  && conflictingRows(current, previous.getValue(), errors, reason)) return;
            }
            broad.add(current);
          }
          ending.add(current);
        }
      }
    }
  }

  private static boolean conflictingRows(IndexedCoverage first, IndexedCoverage second,
      Map<MonthlyPriceRequest, Set<Reason>> errors, Reason reason) {
    if (second == null || first.row() == second.row()) return false;
    first.row().requests().forEach(request -> add(errors, request, reason));
    second.row().requests().forEach(request -> add(errors, request, reason));
    return true;
  }

  private static boolean endsBefore(QuantityInterval existing, QuantityInterval starting) {
    if (existing.upper() == null || starting.lower() == null) return false;
    int comparison = existing.upper().compareTo(starting.lower());
    return comparison < 0 || comparison == 0 && (!existing.upperInclusive() || !starting.lowerInclusive());
  }

  private static void propagateDependencyChanges(
      MonthlyAdoptionPlan plan, Map<MonthlyPriceRequest, Set<Reason>> conflicts) {
    var parents = parentsByDependency(plan);
    var seen = new HashSet<MonthlyPriceRequest>(conflicts.keySet());
    var pending = new ArrayDeque<MonthlyPriceRequest>(seen);
    while (!pending.isEmpty()) {
      for (var parent : parents.getOrDefault(pending.removeFirst(), List.of())) {
        add(conflicts, parent, Reason.DEPENDENCY_CHANGED);
        if (seen.add(parent)) pending.add(parent);
      }
    }
  }

  private static Map<MonthlyPriceRequest, List<MonthlyPriceRequest>> parentsByDependency(
      MonthlyAdoptionPlan plan) {
    var parents = new HashMap<MonthlyPriceRequest, List<MonthlyPriceRequest>>();
    plan.selections().forEach((parent, selected) -> selected.fact().dependencies().forEach(
        dependency -> parents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(parent)));
    return parents;
  }

  private static Map<String, Use> usesByPath(MonthlyAdoptionPlan plan) {
    var uses = new HashMap<String, Use>();
    for (Use use : plan.uses()) {
      String path = pathKey(use.path());
      uses.put(path, new Use(path, use.request(), use.cumulativeQuantity()));
    }
    return uses;
  }

  private static Set<String> normalizedPaths(Set<String> paths) {
    var normalized = new HashSet<String>();
    for (String path : paths) normalized.add(pathKey(path));
    return normalized;
  }

  private static String pathKey(String path) {
    String normalized = path.trim();
    int start = 0, end = normalized.length();
    while (start < end && normalized.charAt(start) == '/') start++;
    while (end > start && normalized.charAt(end - 1) == '/') end--;
    return start == end ? "/" : normalized.substring(start, end);
  }

  private static boolean hasAncestor(String path, Set<String> ancestors) {
    if (!path.equals("/") && ancestors.contains("/")) return true;
    int slash = path.indexOf('/');
    while (slash >= 0) {
      if (ancestors.contains(path.substring(0, slash))) return true;
      slash = path.indexOf('/', slash + 1);
    }
    return false;
  }

  private static void add(
      Map<MonthlyPriceRequest, Set<Reason>> reasons, MonthlyPriceRequest request, Reason reason) {
    reasons.computeIfAbsent(request, ignored -> EnumSet.noneOf(Reason.class)).add(reason);
  }

  private static boolean sameNumber(BigDecimal first, BigDecimal second) {
    return first == null ? second == null : second != null && first.compareTo(second) == 0;
  }
}
