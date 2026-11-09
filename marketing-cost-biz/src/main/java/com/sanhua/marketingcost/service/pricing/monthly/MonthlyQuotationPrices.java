package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Binds existing quotation price inputs to immutable monthly facts, without adopting on reads. */
@Component
public final class MonthlyQuotationPrices {
  public static final int MAX_BATCH_OBJECTS = 64;
  public enum Purpose { MATERIAL, SCRAP, PACKAGE }

  /** Null tax means the legacy consumer uses the source's amount column without a tax conversion. */
  public record Item(String materialCode, String organization, String unit, String specification,
      TaxBasis taxBasis, String supplier, NonCuConditions conditions, BigDecimal basisQuantity,
      Purpose purpose) {}

  public record Bound(MonthlyPriceRequest request, Candidate candidate) {}

  /** Consumer provenance and supplier review remain local even when saved price facts are shared. */
  public record Demand(Context context, List<Item> items) {
    public Demand { Objects.requireNonNull(context); items = List.copyOf(items); }
  }

  public record Batch(Map<Item, Bound> values, Map<Item, Problem> problems,
      Map<MonthlyPriceRequest, Candidate> candidates) {
    public Batch {
      values = Map.copyOf(values);
      problems = Map.copyOf(problems);
      candidates = Map.copyOf(candidates);
    }
  }

  /** Only saved facts can serve the first planning read; new candidates stay consumer-local. */
  public record Prepared(Demand demand, Batch batch) {
    public Prepared { Objects.requireNonNull(demand); Objects.requireNonNull(batch); }
  }
  private static final class InitialRead {
    private Prepared value;
    InitialRead(Prepared value) { this.value=value; }
  }
  private final ThreadLocal<InitialRead> prepared = new ThreadLocal<>();

  public List<Prepared> prepareBatch(List<Demand> demands) {
    var stable = List.copyOf(demands);
    var batches = readBatch(stable, false);
    var result = new ArrayList<Prepared>(stable.size());
    for (int i = 0; i < stable.size(); i++) {
      var demand = stable.get(i);
      result.add(new Prepared(demand, batches.get(i)));
    }
    return List.copyOf(result);
  }

  public <T> T usePrepared(Prepared input, java.util.function.Supplier<T> work) {
    if (prepared.get() != null) throw new IllegalStateException("批量取价不能嵌套");
    prepared.set(new InitialRead(Objects.requireNonNull(input)));
    try { return work.get(); } finally { prepared.remove(); }
  }

  private Batch takePrepared(Context context, Collection<Item> items) {
    var initial = prepared.get();
    if (initial == null || initial.value == null) return null;
    var input = initial.value;
    // Only the first actual read can consume it. All retries and final adoption projection are fresh.
    initial.value = null;
    var expected = input.demand().context();
    if (!Objects.equals(context.businessUnitType(), expected.businessUnitType())
        || !Objects.equals(context.oaNo(), expected.oaNo())
        || !Objects.equals(context.oaFormItemId(), expected.oaFormItemId())
        || !Objects.equals(context.productCode(), expected.productCode())
        || !Objects.equals(context.month(), expected.month())
        || !Objects.equals(context.priceAsOfTime(), expected.priceAsOfTime())
        || !Objects.equals(context.consumerOrg(), expected.consumerOrg())
        || !Objects.equals(context.annualUsage(), expected.annualUsage()))
      throw new IllegalArgumentException("批量取价资料不属于当前客户、产品、月份或取价条件");
    if (!new HashSet<>(items).equals(new HashSet<>(input.demand().items()))) return null;
    if (!input.batch().problems().isEmpty()
        || input.batch().candidates().values().stream().anyMatch(value -> value.selection().monthlyPriceId() == null))
      return null;
    return input.batch();
  }

  public static Item item(QuotationPriceSession.Input input) {
    return new Item(input.materialCode(), input.organization(), input.unit(), input.specification(),
        input.taxBasis(), input.supplier(), input.conditions(), input.basisQuantity(),
        Purpose.valueOf(input.purpose().name()));
  }

  private final MonthlyMaterialPrices prices;
  private final HouseholdMonthlyPriceSource household;
  private final BomSpecificationPriceIdentity specifications;

  public MonthlyQuotationPrices(MonthlyMaterialPrices prices, HouseholdMonthlyPriceSource household) {
    this(prices, household, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public MonthlyQuotationPrices(MonthlyMaterialPrices prices, HouseholdMonthlyPriceSource household,
      BomSpecificationPriceIdentity specifications) {
    this.prices = Objects.requireNonNull(prices);
    this.household = Objects.requireNonNull(household);
    this.specifications = specifications;
  }

  public Batch read(Context context, Collection<Item> items) {
    var captured = takePrepared(context, items);
    return captured != null ? captured : readBatch(List.of(new Demand(context, List.copyOf(items)))).getFirst();
  }

  /**
   * One bounded lookup of the complete request union, with original-order consumer results.
   * This is a read operation, not a cache or first-use adoption. Each call reads current saved facts.
   * Missing sources are resolved in each consumer's own approval/price-purpose context.
   */
  public List<Batch> readBatch(List<Demand> demands) {
    return readBatch(demands, true);
  }

  private List<Batch> readBatch(List<Demand> demands, boolean resolveMissing) {
    var stable = List.copyOf(demands);
    if (stable.size() > MAX_BATCH_OBJECTS)
      throw new IllegalArgumentException("月价读取每批最多 " + MAX_BATCH_OBJECTS + " 个产品对象");
    if (stable.isEmpty()) return List.of();
    var failures = new ArrayList<Map<Item, Problem>>();
    var requested = new ArrayList<Map<Item, List<MonthlyPriceRequest>>>();
    for (var demand : stable) {
      var inputs = new LinkedHashMap<Item, List<MonthlyPriceRequest>>();
      var issues = new LinkedHashMap<Item, Problem>();
      for (var item : demand.items()) {
        try { inputs.putIfAbsent(item, requestsFor(demand.context(), item)); }
        catch (MonthlyPriceProblem missing) { issues.put(item, new Problem(missing.reason(), missing.getMessage())); }
      }
      requested.add(inputs); failures.add(issues);
    }
    var all = new LinkedHashSet<MonthlyPriceRequest>();
    requested.forEach(items -> items.values().forEach(all::addAll));
    var saved = prices.lookup(all);
    var result = new ArrayList<Batch>(stable.size());
    for (int i = 0; i < stable.size(); i++) {
      var batch = read(stable.get(i).context(), requested.get(i), saved, resolveMissing);
      var issues = new LinkedHashMap<>(batch.problems()); issues.putAll(failures.get(i));
      result.add(new Batch(batch.values(), issues, batch.candidates()));
    }
    return List.copyOf(result);
  }

  private Batch read(Context context, Map<Item, List<MonthlyPriceRequest>> requests, Lookup saved, boolean resolveMissing) {
    var found = new LinkedHashMap<MonthlyPriceRequest, Candidate>();
    var pending = new ArrayDeque<MonthlyPriceRequest>();
    requests.values().forEach(pending::addAll);
    while (!pending.isEmpty()) {
      var request = pending.removeFirst();
      var selected = saved.values().get(request);
      if (selected != null && found.putIfAbsent(request, new Candidate(selected, null)) == null)
        pending.addAll(selected.fact().dependencies());
    }
    // Prefetch never resolves a current source early or retains its approval while other customers run.
    if (!resolveMissing) return bind(requests, found, Map.of(), false);
    var problems = new LinkedHashMap<MonthlyPriceRequest, Problem>();
    for (var purpose : Purpose.values()) {
      var missing = new LinkedHashSet<MonthlyPriceRequest>();
      requests.forEach((item, variants) -> {
        if (item.purpose() == purpose && variants.stream().noneMatch(found::containsKey))
          missing.addAll(variants);
      });
      if (missing.isEmpty()) continue;
      var sourceContext = purpose == Purpose.PACKAGE
          ? new Context(context.businessUnitType(), context.oaNo(), context.oaFormItemId(), context.productCode(),
              context.month(), context.priceAsOfTime(), context.actor(), context.consumerOrg(),
              context.supplierReviews().forSupplementalPackaging(), context.annualUsage())
          : context;
      var candidates = purpose == Purpose.SCRAP
          ? prices.resolveScrapCandidates(sourceContext, missing) : prices.resolveCandidates(sourceContext, missing);
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
    for (var item : items) {
      try { requests.putIfAbsent(item, requestsFor(context, item)); }
      catch (MonthlyPriceProblem missing) {
        // A code-less, unpriced child may be suppressed by a priced parent. Its ordinary
        // preparation reports this gap only if the settlement engine actually uses it.
        if (item.materialCode() != null && !item.materialCode().isBlank()) throw missing;
      }
    }
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

  String specificationUnit(Context context,String organization,String specification) {
    return specifications.resolve(context,organization,specification).unit();
  }

  List<MonthlyPriceRequest> requestsFor(Context context, Item item) {
    Objects.requireNonNull(item.purpose(), "Price purpose is required");
    if (item.materialCode() != null && !item.materialCode().isBlank()) return requests(context, item);
    if (specifications == null)
      throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.MISSING, "BOM规格取价查询未配置");
    var identity = specifications.resolve(context, item.organization(), item.specification());
    String code = identity.materialCode();
    String unit = item.unit() == null || item.unit().isBlank() ? identity.unit() : item.unit();
    var taxes = item.taxBasis() == null ? List.of(TaxBasis.INCLUDED,TaxBasis.EXCLUDED) : List.of(item.taxBasis());
    return taxes.stream().map(tax -> new MonthlyPriceRequest(new MonthlyPriceFamilyKey(context.month(),
        context.businessUnitType(),code,item.specification(),unit,"CNY",tax,item.supplier(),true),
        item.organization(),item.conditions(),item.basisQuantity(),
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
