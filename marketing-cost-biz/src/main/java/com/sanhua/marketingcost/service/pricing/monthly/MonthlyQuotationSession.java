package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** One attempt's captured sources and uses; adoption is performed by the quotation orchestrator. */
public final class MonthlyQuotationSession implements QuotationPriceSession {
  private final MonthlyMaterialPrices.Context context;
  private final BigDecimal customerCopper;
  private final MonthlyQuotationPrices prices;
  private final MonthlyPriceScenarios scenarios;
  private final MonthlyPriceStorage storage;
  private final MonthlyPriceDocumentCodec codec;
  private final BoardTerms boardTerms;
  private final Map<Input, Value> values = new LinkedHashMap<>();
  private final Map<MonthlyPriceRequest, Value> capturedValues = new LinkedHashMap<>();
  private final Map<MonthlyPriceFamilyKey, List<Value>> capturedFamilies = new HashMap<>();
  private final Map<String, MonthlyAdoptionPlan.Use> uses = new LinkedHashMap<>();
  private MonthlyBomPricingScope.Scope bomScope;

  public MonthlyQuotationSession(MonthlyMaterialPrices.Context context, BigDecimal customerCopper,
      MonthlyQuotationPrices prices, MonthlyPriceScenarios scenarios, MonthlyPriceStorage storage,
      MonthlyPriceDocumentCodec codec) {
    this(context, customerCopper, prices, scenarios, storage, codec, null);
  }

  public MonthlyQuotationSession(MonthlyMaterialPrices.Context context, BigDecimal customerCopper,
      MonthlyQuotationPrices prices, MonthlyPriceScenarios scenarios, MonthlyPriceStorage storage,
      MonthlyPriceDocumentCodec codec, BoardTerms boardTerms) {
    this.context = Objects.requireNonNull(context);
    this.customerCopper = customerCopper;
    this.prices = Objects.requireNonNull(prices);
    this.scenarios = Objects.requireNonNull(scenarios);
    this.storage = Objects.requireNonNull(storage);
    this.codec = Objects.requireNonNull(codec);
    this.boardTerms = boardTerms;
  }

  @Override public MonthlyMaterialPrices.Context context() { return context; }

  @Override public Optional<MonthlyBomPricingScope.Scope> bomScope() { return Optional.ofNullable(bomScope); }

  @Override public NonCuConditions conditions(String organization) {
    return "220".equals(organization) ? new NonCuConditions(boardTerms) : NonCuConditions.empty();
  }

  @Override public void bindScope(MonthlyBomPricingScope.Scope scope) {
    Objects.requireNonNull(scope);
    MonthlyBomPricingScope.validate(scope.original());
    if (bomScope != null && !bomScope.equals(scope)) throw changed();
    if (!uses.isEmpty()) throw new IllegalStateException("必须先确定 BOM 计价范围，再记录实际用价");
    for (var node : scope.original()) if (!context.businessUnitType().equals(node.request().family().businessUnitType())
        || !context.month().equals(node.request().family().month()))
      throw new IllegalArgumentException("BOM 计价范围与当前报价月份或业务单元不一致");
    bomScope = scope;
  }

  @Override public Read read(Collection<Input> inputs) {
    var missing = new LinkedHashMap<Input, MonthlyQuotationPrices.Item>();
    var issues = new LinkedHashMap<Input, MonthlyMaterialPrices.Problem>();
    for (var input : inputs) if (!values.containsKey(input)) {
      var item = new MonthlyQuotationPrices.Item(input.materialCode(), input.organization(),
          input.unit(), input.specification(), input.taxBasis(), input.supplier(), input.conditions(),
          input.basisQuantity(), MonthlyQuotationPrices.Purpose.valueOf(input.purpose().name()));
      var matches = MonthlyQuotationPrices.requests(context, item).stream()
          .map(this::captured).filter(Objects::nonNull).toList();
      if (matches.size() == 1) {
        var value = matches.get(0);
        capturedValues.putIfAbsent(value.request(), value);
        values.put(input, value);
      } else if (matches.size() > 1) {
        issues.put(input, new MonthlyMaterialPrices.Problem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,
            "同一备价输入存在多个税口径，不能自动选择或换算"));
      } else missing.putIfAbsent(input, item);
    }
    if (!missing.isEmpty()) {
      var batch = prices.read(context, missing.values());
      var captured = new LinkedHashMap<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection>();
      batch.candidates().forEach((request, candidate) -> captured.put(request, candidate.selection()));
      var dates = new HashMap<MonthlyPriceRequest, LocalDate>();
      var ids = captured.values().stream().map(MonthlyAdoptionPlan.Selection::monthlyPriceId)
          .filter(Objects::nonNull).distinct().toList();
      var savedDates = new HashMap<Long, LocalDate>();
      if (!ids.isEmpty()) for (var saved : storage.readByIds(context.businessUnitType(), ids)) {
        var origin = codec.decode(codec.wrap(saved.firstUseJson()), MonthlyMaterialPriceService.FirstUse.class);
        savedDates.put(saved.id(), origin.origin().priceAsOfTime().toLocalDate());
      }
      captured.forEach((request, selected) -> dates.put(request, selected.monthlyPriceId() == null
          ? context.priceAsOfTime().toLocalDate() : Objects.requireNonNull(savedDates.get(selected.monthlyPriceId()))));
      var paired = scenarios.preview(captured, customerCopper, dates);
      captured.forEach((request, selected) -> {
        var pair = paired.get(request);
        remember(new Value(request, selected, pair.financialPrice(), pair.customerPrice()));
      });
      missing.forEach((input, item) -> {
        var bound = batch.values().get(item);
        if (bound != null) {
          values.put(input, capturedValues.get(bound.request()));
        } else issues.put(input, Objects.requireNonNull(batch.problems().get(item)));
      });
    }
    var result = new LinkedHashMap<Input, Value>();
    for (var input : inputs) if (values.containsKey(input)) result.put(input, values.get(input));
    return new Read(result, issues);
  }

  /** Reuse only the captured applicability, keeping each consumer's organization and conditions. */
  private Value captured(MonthlyPriceRequest request) {
    var exact = capturedValues.get(request);
    if (exact != null) return exact;
    Value match = null;
    for (var value : capturedFamilies.getOrDefault(request.family(), List.of())) {
      if (!value.request().priceOrgCode().equals(request.priceOrgCode())
          || !value.selection().applicability().matches(request)) continue;
      var rebound = new Value(request, value.selection(), value.financialPrice(), value.customerPrice());
      if (match != null && !samePrice(match, rebound)) throw changed();
      match = rebound;
    }
    return match;
  }

  private void remember(Value value) {
    var previous = captured(value.request());
    if (previous != null && !samePrice(previous, value)) throw changed();
    capturedValues.putIfAbsent(value.request(), value);
    if (previous == null) capturedFamilies.computeIfAbsent(value.request().family(), ignored -> new ArrayList<>())
        .add(value);
  }

  private static boolean samePrice(Value left, Value right) {
    return left.selection().equals(right.selection())
        && left.financialPrice().compareTo(right.financialPrice()) == 0
        && left.customerPrice().compareTo(right.customerPrice()) == 0;
  }

  private static MonthlyPriceProblem changed() {
    return new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,
        "同一次报价备价的价格依据发生变化");
  }

  @Override public void consume(String path, Input input, BigDecimal effectiveQuantity) {
    var selected = Objects.requireNonNull(values.get(input), "Read a valid price before recording its use");
    consume(path, selected, effectiveQuantity);
  }

  @Override public void consume(String path, Value selected, BigDecimal effectiveQuantity) {
    var captured = capturedValues.get(selected.request());
    if (captured == null || !samePrice(captured, selected))
      throw new IllegalArgumentException("消费的月价不属于本次已读取依据");
    var use = new MonthlyAdoptionPlan.Use(MonthlyBomPricingScope.pathKey(path), selected.request(), effectiveQuantity);
    if (bomScope != null) {
      boolean household = false;
      for (var cut : bomScope.household()) {
        String root = MonthlyBomPricingScope.pathKey(cut.path());
        if (use.path().startsWith(root + "/")) throw changed();
        if (use.path().equals(root)) household = true;
      }
      if (household != (selected.selection().fact().type() == MonthlyAdoptionPlan.PricingType.HOUSEHOLD_INQUIRY))
        throw changed();
    }
    var previous = uses.putIfAbsent(use.path(), use);
    if (previous != null && !previous.equals(use))
      throw new IllegalArgumentException("同一计价路径不能对应不同物料或数量");
  }

  @Override public MonthlyAdoptionPlan plan() {
    var consumed = new LinkedHashMap<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection>();
    var pending = new ArrayDeque<MonthlyPriceRequest>();
    uses.values().forEach(use -> pending.add(use.request()));
    while (!pending.isEmpty()) {
      var request = pending.removeFirst();
      if (consumed.containsKey(request)) continue;
      var selected = Objects.requireNonNull(capturedValues.get(request), "Consumed price dependency is missing").selection();
      consumed.put(request, selected);
      pending.addAll(selected.fact().dependencies());
    }
    var observations = new LinkedHashSet<MonthlyPriceRequest>();
    if (bomScope != null) bomScope.ordinary().forEach(node -> observations.add(node.request()));
    return new MonthlyAdoptionPlan(consumed, List.copyOf(uses.values()),
        bomScope == null ? Set.of() : bomScope.suppressedPaths(), observations);
  }
}
