package com.sanhua.marketingcost.service.pricing.monthly;

import java.util.*;

/** Actual base-price uses captured with one preparation row, including its dependency closure. */
public record MonthlyPreparationInputs(int schemaVersion, String rootPath,
    List<Price> prices, List<MonthlyAdoptionPlan.Use> uses, Set<String> suppressedPaths) {
  public record Price(MonthlyPriceRequest request, MonthlyAdoptionPlan.Selection selection) {}

  public MonthlyPreparationInputs {
    if (schemaVersion != 1) throw new IllegalArgumentException("未知月价准备依据版本");
    rootPath = MonthlyBomPricingScope.pathKey(rootPath);
    prices = List.copyOf(prices).stream().sorted(Comparator.comparing(price -> price.request().toString())).toList();
    uses = List.copyOf(uses).stream().sorted(Comparator.comparing(MonthlyAdoptionPlan.Use::path)).toList();
    suppressedPaths = Set.copyOf(suppressedPaths);
    var selections = selections(prices);
    var paths = new HashSet<String>();
    for (var use : uses) if (!within(use.path(), rootPath) || !paths.add(use.path()))
      throw new IllegalArgumentException("月价准备依据含重复或其他节点的用量");
    if (!closure(selections, uses).keySet().equals(selections.keySet()))
      throw new IllegalArgumentException("月价准备依据包含未消费且非依赖的价格");
    for (String path : suppressedPaths) if (!within(path, rootPath))
      throw new IllegalArgumentException("月价准备依据含其他节点的剪枝路径");
  }

  public static MonthlyPreparationInputs capture(MonthlyAdoptionPlan plan, String path) {
    String root = MonthlyBomPricingScope.pathKey(path);
    var uses = plan.uses().stream().filter(use -> within(use.path(), root))
        .filter(use -> plan.selections().get(use.request()).fact().type() != MonthlyAdoptionPlan.PricingType.HOUSEHOLD_INQUIRY
            || MonthlyBomPricingScope.pathKey(use.path()).equals(root)).toList();
    var selected = closure(plan.selections(), uses);
    var prices = selected.entrySet().stream().map(entry -> new Price(entry.getKey(), entry.getValue())).toList();
    var suppressed = new TreeSet<String>();
    plan.suppressedPaths().stream().filter(value -> uses.stream().anyMatch(use ->
        plan.selections().get(use.request()).fact().type() == MonthlyAdoptionPlan.PricingType.HOUSEHOLD_INQUIRY
            && within(value, MonthlyBomPricingScope.pathKey(use.path())))).forEach(suppressed::add);
    return new MonthlyPreparationInputs(1, root, prices, uses, suppressed);
  }

  public MonthlyAdoptionPlan plan() {
    return new MonthlyAdoptionPlan(selections(prices), uses, suppressedPaths);
  }

  private static boolean within(String path, String root) {
    String normalized = MonthlyBomPricingScope.pathKey(path);
    return normalized.equals(root) || normalized.startsWith(root + "/");
  }

  private static Map<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection> selections(List<Price> prices) {
    var result = new LinkedHashMap<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection>();
    for (var price : prices) {
      Objects.requireNonNull(price.request()); Objects.requireNonNull(price.selection());
      if (!price.selection().applicability().matches(price.request())
          || result.putIfAbsent(price.request(), price.selection()) != null)
        throw new IllegalArgumentException("月价准备依据的请求重复或不适用");
    }
    return result;
  }

  private static Map<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection> closure(
      Map<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection> available, List<MonthlyAdoptionPlan.Use> uses) {
    var result = new LinkedHashMap<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection>();
    var pending = new ArrayDeque<MonthlyPriceRequest>();
    uses.forEach(use -> pending.add(use.request()));
    while (!pending.isEmpty()) {
      var request = pending.removeFirst();
      if (result.containsKey(request)) continue;
      var selected = Objects.requireNonNull(available.get(request), "月价准备依据缺少实际价格或必要依赖");
      result.put(request, selected);
      pending.addAll(selected.fact().dependencies());
    }
    return result;
  }
}
