package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Binds existing quotation price inputs to immutable monthly facts, without adopting on reads. */
@Component
public final class MonthlyQuotationPrices {
  public enum Purpose { MATERIAL, SCRAP }

  /** Null tax means the legacy consumer uses the source's amount column without a tax conversion. */
  public record Item(String materialCode, String organization, String unit, String specification,
      TaxBasis taxBasis, String supplier, NonCuConditions conditions, BigDecimal basisQuantity,
      Purpose purpose) {}

  public record Bound(MonthlyPriceRequest request, Candidate candidate) {}

  public record Batch(Map<Item, Bound> values, Map<Item, Problem> problems,
      Map<MonthlyPriceRequest, Candidate> candidates) {
    public Batch {
      values = Map.copyOf(values);
      problems = Map.copyOf(problems);
      candidates = Map.copyOf(candidates);
    }
  }

  private final MonthlyMaterialPrices prices;
  private final HouseholdMonthlyPriceSource household;

  public MonthlyQuotationPrices(MonthlyMaterialPrices prices, HouseholdMonthlyPriceSource household) {
    this.prices = Objects.requireNonNull(prices);
    this.household = Objects.requireNonNull(household);
  }

  public Batch read(Context context, Collection<Item> items) {
    Objects.requireNonNull(context);
    var requests = new LinkedHashMap<Item, List<MonthlyPriceRequest>>();
    for (var item : items) {
      Objects.requireNonNull(item.purpose(), "Price purpose is required");
      requests.putIfAbsent(item, requests(context, item));
    }
    var all = new LinkedHashSet<MonthlyPriceRequest>();
    requests.values().forEach(all::addAll);
    var saved = prices.lookup(all);
    var found = new LinkedHashMap<MonthlyPriceRequest, Candidate>();
    saved.values().forEach((request, selection) -> found.put(request, new Candidate(selection, null)));
    var problems = new LinkedHashMap<MonthlyPriceRequest, Problem>();
    for (var purpose : Purpose.values()) {
      var missing = new LinkedHashSet<MonthlyPriceRequest>();
      requests.forEach((item, variants) -> {
        if (item.purpose() == purpose && variants.stream().noneMatch(found::containsKey))
          missing.addAll(variants);
      });
      if (missing.isEmpty()) continue;
      var candidates = purpose == Purpose.SCRAP
          ? prices.resolveScrapCandidates(context, missing) : prices.resolveCandidates(context, missing);
      candidates.values().forEach((request, candidate) -> {
        var previous = found.putIfAbsent(request, candidate);
        if (previous != null && !previous.equals(candidate))
          throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,
              "价格依赖在本次备价期间发生变化，请重新读取");
      });
      problems.putAll(candidates.problems());
    }
    return bind(requests, found, problems, false);
  }

  /** Reads saved types and household candidates only; ordinary gaps belong to later preparation. */
  public Batch probeHousehold(Context context, Collection<Item> items) {
    var requests = new LinkedHashMap<Item, List<MonthlyPriceRequest>>();
    items.forEach(item -> requests.putIfAbsent(item, requests(context, item)));
    var all = new LinkedHashSet<MonthlyPriceRequest>(); requests.values().forEach(all::addAll);
    var saved = prices.lookup(all);
    var found = new LinkedHashMap<MonthlyPriceRequest, Candidate>();
    saved.values().forEach((request, selection) -> found.put(request, new Candidate(selection, null)));
    var missing = new LinkedHashSet<MonthlyPriceRequest>();
    requests.forEach((item, variants) -> {
      if (item.purpose() == Purpose.MATERIAL && variants.stream().noneMatch(found::containsKey)) missing.addAll(variants);
    });
    var householdCandidates = household.read(context, missing);
    found.putAll(householdCandidates.values());
    return bind(requests, found, householdCandidates.problems(), true);
  }

  private static Batch bind(Map<Item, List<MonthlyPriceRequest>> requests,
      Map<MonthlyPriceRequest, Candidate> found, Map<MonthlyPriceRequest, Problem> problems,
      boolean scopeOnly) {
    var values = new LinkedHashMap<Item, Bound>();
    var issues = new LinkedHashMap<Item, Problem>();
    var used = new LinkedHashMap<MonthlyPriceRequest, Candidate>();
    requests.forEach((item, variants) -> {
      var matches = variants.stream().filter(found::containsKey).toList();
      if (matches.size() == 1) {
        var request = matches.get(0);
        values.put(item, new Bound(request, found.get(request)));
        includeDependencies(request, found, used);
      } else if (matches.size() > 1) {
        issues.put(item, new Problem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,
            "同一备价输入存在多个税口径，不能自动选择或换算"));
      } else {
        var issue = variants.stream().map(problems::get).filter(Objects::nonNull)
            // An inapplicable tax variant must not hide a real source error in the other variant.
            .min(Comparator.comparingInt(problem -> switch (problem.reason()) {
              case MISSING -> 2;
              case INVALID_INPUT -> 1;
              default -> 0;
            })).orElse(new Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING,
                "没有适用的月价或来源价格"));
        issues.put(item, issue);
      }
    });
    if (scopeOnly) issues.entrySet().removeIf(entry -> entry.getValue().reason() == MonthlyAdoptionRetryPolicy.Failure.MISSING);
    return new Batch(values, issues, used);
  }

  static List<MonthlyPriceRequest> requests(Context context, Item item) {
    var taxes = item.taxBasis() == null
        ? List.of(TaxBasis.INCLUDED, TaxBasis.EXCLUDED) : List.of(item.taxBasis());
    return taxes.stream().map(tax -> new MonthlyPriceRequest(
        new MonthlyPriceFamilyKey(context.month(), context.businessUnitType(), item.materialCode(),
            item.specification(), item.unit(), "CNY", tax, item.supplier()),
        item.organization(), item.conditions(), item.basisQuantity(),
        context.annualUsage() == null ? null : context.annualUsage().piecesIfKnown())).toList();
  }

  private static void includeDependencies(MonthlyPriceRequest request,
      Map<MonthlyPriceRequest, Candidate> found, Map<MonthlyPriceRequest, Candidate> used) {
    if (used.containsKey(request)) return;
    var candidate = found.get(request);
    if (candidate == null) throw new MonthlyPriceProblem(
        MonthlyAdoptionRetryPolicy.Failure.EVIDENCE_UNAVAILABLE, "备价缺少已选价格的依赖依据");
    used.put(request, candidate);
    candidate.selection().fact().dependencies().forEach(dependency -> includeDependencies(dependency, found, used));
  }
}
