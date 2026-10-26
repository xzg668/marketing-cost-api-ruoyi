package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.registry.PreparedFormulaInputs;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.scrap.*;
import com.sanhua.marketingcost.service.settlement.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/** Replays the selected immutable source only. It never reads a current rule, factor or supplier. */
@Component
public final class MonthlyPriceScenarios {
  public record Pair(long monthlyPriceId, String sourceEvidenceHash, BigDecimal financialPrice,
      BigDecimal customerPrice, String customerEvidenceJson) {}

  /** A preview carries amounts only; no database identity or adoption receipt is invented. */
  public record PreviewPair(BigDecimal financialPrice, BigDecimal customerPrice) {}

  public Map<MonthlyPriceRequest, PreviewPair> preview(Map<MonthlyPriceRequest, Selection> candidates,
      BigDecimal customerCopperPerTon, Map<MonthlyPriceRequest, LocalDate> sourceDates) {
    if (customerCopperPerTon != null && customerCopperPerTon.signum() <= 0)
      throw new IllegalArgumentException("Customer copper must be positive CNY/ton");
    // These keys only address the in-memory dependency graph. Selections retain their original IDs.
    var identities = new LinkedHashMap<MonthlyBomPricingScope.PriceIdentity, Long>();
    var keys = new LinkedHashMap<MonthlyPriceRequest, Long>();
    var prices = new LinkedHashMap<Long, Selection>();
    var dates = new HashMap<Long, LocalDate>();
    candidates.forEach((request, selected) -> {
      if (!selected.applicability().matches(request))
        throw new IllegalArgumentException("Candidate does not apply to preview request");
      var identity = new MonthlyBomPricingScope.PriceIdentity(request.family(), selected.applicability());
      long key = identities.computeIfAbsent(identity, ignored -> (long) identities.size() + 1);
      keys.put(request, key);
      var previous = prices.putIfAbsent(key, selected);
      if (previous != null && !previous.equals(selected))
        throw new IllegalArgumentException("Conflicting preview candidates for the same price identity");
      LocalDate date = sourceDates.get(request);
      if (date != null) {
        var prior = dates.putIfAbsent(key, date);
        if (prior != null && !prior.equals(date))
          throw new IllegalArgumentException("Conflicting preview source dates");
      }
    });
    var projected = new LinkedHashMap<Long, Pair>();
    var visiting = new HashSet<Long>();
    for (long key : prices.keySet())
      project(key, customerCopperPerTon, prices, candidates, keys, dates, projected, visiting);
    var result = new LinkedHashMap<MonthlyPriceRequest, PreviewPair>();
    keys.forEach((request, key) -> {
      var pair = projected.get(key);
      result.put(request, new PreviewPair(pair.financialPrice(), pair.customerPrice()));
    });
    return Map.copyOf(result);
  }

  private final MonthlyPriceDocumentCodec codec;
  private final OriginalLinkedRuleCalculator linked;
  private final OriginalRangeRuleCalculator originalRange;
  private final OriginalRangeRuleSnapshot rangeSnapshots;
  private final RangePriceResolver legacyRange;
  private final PriceLinkedFormulaPreviewServiceImpl legacyLinked;
  private final SettlementPriceCalculator settlement;
  private final ScrapPriceCalculator scrap;

  public MonthlyPriceScenarios(MonthlyPriceDocumentCodec codec, OriginalLinkedRuleCalculator linked,
      OriginalRangeRuleCalculator originalRange, OriginalRangeRuleSnapshot rangeSnapshots,
      RangePriceResolver legacyRange, PriceLinkedFormulaPreviewServiceImpl legacyLinked,
      SettlementPriceCalculator settlement, ScrapPriceCalculator scrap) {
    this.codec = codec; this.linked = linked; this.originalRange = originalRange;
    this.rangeSnapshots = rangeSnapshots; this.legacyRange = legacyRange; this.legacyLinked = legacyLinked;
    this.settlement = settlement; this.scrap = scrap;
  }

  public Map<Long, Pair> calculate(Map<MonthlyPriceRequest, Selection> requests, BigDecimal customerCopperPerTon) {
    return calculate(requests, customerCopperPerTon, Map.of());
  }

  /** Undated legacy bands use the immutable first-adoption day, never today's or a later quote's day. */
  public Map<Long, Pair> calculate(Map<MonthlyPriceRequest, Selection> requests, BigDecimal customerCopperPerTon,
      Map<Long, LocalDate> firstAdoptionDates) {
    if (customerCopperPerTon != null && customerCopperPerTon.signum() <= 0)
      throw new IllegalArgumentException("Customer copper must be positive CNY/ton");
    var byId = new LinkedHashMap<Long, Selection>();
    for (var price : requests.values()) {
      if (price.monthlyPriceId() == null || price.monthlyPriceId() <= 0)
        throw new IllegalArgumentException("Scenarios require adopted prices");
      var previous = byId.putIfAbsent(price.monthlyPriceId(), price);
      if (previous != null && !previous.equals(price)) throw new IllegalArgumentException("Conflicting monthly price identity");
    }
    var result = new LinkedHashMap<Long, Pair>();
    var visiting = new HashSet<Long>();
    var keys = new HashMap<MonthlyPriceRequest, Long>();
    requests.forEach((request, selected) -> keys.put(request, selected.monthlyPriceId()));
    for (long id : byId.keySet()) project(id, customerCopperPerTon, byId, requests, keys, firstAdoptionDates, result, visiting);
    return Map.copyOf(result);
  }

  private Pair project(long id, BigDecimal copper, Map<Long, Selection> prices, Map<MonthlyPriceRequest, Selection> requests,
      Map<MonthlyPriceRequest, Long> keys, Map<Long, LocalDate> firstAdoptionDates,
      Map<Long, Pair> result, Set<Long> visiting) {
    if (result.containsKey(id)) return result.get(id);
    if (!visiting.add(id)) throw new IllegalArgumentException("Cyclic monthly price evidence");
    var selected = Objects.requireNonNull(prices.get(id), "Missing adopted dependency");
    var fact = selected.fact();
    var root = read(fact.evidence().json(), JsonNode.class);
    if (root.path("snapshotSchema").asInt() != 1) throw new IllegalArgumentException("Unknown price evidence version");
    String kind = root.path("sourceKind").asText();
    BigDecimal financial;
    BigDecimal customer;
    switch (kind) {
      case "HOUSEHOLD_INQUIRY" -> {
        var source = read(fact.evidence().json(), HouseholdMonthlyPriceSource.Snapshot.class).source().evidence().source();
        financial = new BigDecimal(source.unitPrice()); customer = financial;
        if (fact.householdRate() == null || fact.householdRate().compareTo(new BigDecimal(source.householdRate())) != 0)
          throw new IllegalArgumentException("Adopted household H differs from source evidence");
      }
      case "PURCHASE_FIXED", "TECH_FIXED" -> {
        financial = new BigDecimal(root.path("source").path("selected").path("fixedPrice").asText()); customer = financial;
      }
      case "ODS_ORDER", "ODS_TIER" -> {
        financial = new BigDecimal(root.path("source").path("selected").path("price").asText()); customer = financial;
      }
      case "RANGE_QTY" -> {
        var source = read(fact.evidence().json(), MonthlyQuantityRangeSource.Snapshot.class);
        financial = "price_incl_tax".equals(source.financialBasis().priceColumn())
            ? source.source().selected().getPriceInclTax() : source.source().selected().getPriceExclTax();
        customer = financial;
      }
      case "LINKED" -> {
        var source = read(fact.evidence().json(), MonthlyOriginalLinkedPriceSource.Snapshot.class).source();
        var saved = read(source.selectedPublication().getCalculationJson(), FinanceEffectivePriceService.Calculation.class);
        var context = convert(read(source.calculationJson(), JsonNode.class).path("context"), PriceFactorContext.class);
        financial = linked.calculate(saved.linkedRule(), context.financialOnly()).priceExcludingTax().setScale(18, RoundingMode.HALF_UP);
        customer = linked.calculate(saved.linkedRule(), customer(context, copper)).priceExcludingTax().setScale(18, RoundingMode.HALF_UP);
      }
      case "RANGE_FACTOR" -> {
        var source = read(fact.evidence().json(), MonthlyOriginalRangePriceSource.Snapshot.class).source();
        var saved = read(source.calculationJson(), OriginalRangePriceRuntime.Calculation.class);
        var rule = rangeSnapshots.read(source.selected()).rule();
        financial = originalRange.calculate(rule, saved.context().financialOnly()).priceExcludingTax().setScale(18, RoundingMode.HALF_UP);
        customer = originalRange.calculate(rule, customer(saved.context(), copper)).priceExcludingTax().setScale(18, RoundingMode.HALF_UP);
      }
      case "RANGE_LEGACY_FACTOR" -> {
        var source = read(fact.evidence().json(), MonthlyLegacyRangePriceSource.Snapshot.class).source();
        var request = requests.entrySet().stream().filter(entry -> keys.get(entry.getKey()) == id)
            .map(Map.Entry::getKey).findFirst().orElseThrow();
        LocalDate adoptionDate = firstAdoptionDates.get(id);
        financial = range(source, source.financialInputs(), request, adoptionDate);
        customer = range(source, customer(source.financialInputs(), copper), request, adoptionDate);
      }
      case "SCRAP_PRICE" -> {
        var snapshot = read(fact.evidence().json(), MonthlyScrapPriceSource.Snapshot.class);
        var source = snapshot.source();
        var captured = source.candidates().stream().filter(value -> Objects.equals(value.original().getId(), source.selected().getId())).findFirst().orElseThrow();
        var row = source.selected();
        row.setPriceMonth(snapshot.requestedMonth());
        financial = scrap.calculate(scrap.prepare(row, captured.input().factors()));
        customer = scrap.calculate(scrap.prepare(row, captured.input().factors() == null ? null
            : customer(captured.input().factors(), copper)));
      }
      case "SETTLE_FIXED", "SETTLE_LINKED" -> {
        var source = root.path("source");
        var capturedRule = (com.fasterxml.jackson.databind.node.ObjectNode) source.path("rule").deepCopy();
        var sourceRows = capturedRule.remove("sourceRows");
        if (sourceRows != null) capturedRule.put("sourceRowsJson", sourceRows.toString());
        var rule = convert(capturedRule, SettlementPriceRecord.class);
        var base = source.path("base").isNull() ? null : convert(source.path("base"), SettlementBasePrice.class);
        financial = settlement.calculate(rule, base).unitPrice();
        var projected = base == null || copper == null ? base : new SettlementBasePrice(base.id(), base.businessUnitType(),
            base.priceMonth(), base.revisionNo(), base.active(), copper.divide(new BigDecimal("1000")),
            base.zincPrice(), base.unit(), base.taxBasis(), base.sourceKind(), base.sourceFileSha256(),
            base.changeReason(), base.createdBy(), base.createdAt(), base.financial());
        customer = settlement.calculate(rule, projected).unitPrice();
      }
      case "LINKED_LEGACY" -> {
        var source = root.path("source");
        var calculation = source.path("calculation");
        if (!"MONTHLY_LINKED_FORMULA_V1".equals(calculation.path("calculatorVersion").asText()))
          throw new IllegalArgumentException("Unknown captured formula version");
        var rule = convert(source.path("selected"), PriceLinkedItem.class);
        var definitions = list(calculation.path("capturedDefinitions"), PriceVariable.class);
        var bindings = new ArrayList<PriceVariableBinding>();
        if (!calculation.path("capturedBindings").isObject()) throw new IllegalArgumentException("Missing captured formula bindings");
        calculation.path("capturedBindings").elements().forEachRemaining(value -> bindings.add(convert(value, PriceVariableBinding.class)));
        var placeholders = list(calculation.path("capturedPlaceholders"), RowLocalPlaceholder.class);
        var factors = calculation.path("context").isNull() ? null : convert(calculation.path("context"), PriceFactorContext.class);
        var identities = new LinkedHashMap<Long, FactorIdentity>();
        calculation.path("capturedFactorIdentities").fields().forEachRemaining(entry -> identities.put(Long.valueOf(entry.getKey()), convert(entry.getValue(), FactorIdentity.class)));
        var privatePrices = new ArrayList<FactorMonthlyPrice>();
        calculation.path("usedPrivateFactors").elements().forEachRemaining(value -> privatePrices.add(convert(value, FactorMonthlyPrice.class)));
        var references = new LinkedHashMap<String, List<MaterialScrapRef>>();
        calculation.path("usedScrapReferences").fields().forEachRemaining(entry -> references.put(entry.getKey(), list(entry.getValue(), MaterialScrapRef.class)));
        var p0Scrap = new LinkedHashMap<String, PreparedFormulaInputs.ScrapPricePerKgExclTax>();
        var p1Scrap = new LinkedHashMap<String, PreparedFormulaInputs.ScrapPricePerKgExclTax>();
        for (var dependency : fact.dependencies()) {
          var child = Objects.requireNonNull(requests.get(dependency), "Missing captured scrap dependency");
          var pair = project(Objects.requireNonNull(keys.get(dependency), "Missing preview dependency identity"),
              copper, prices, requests, keys, firstAdoptionDates, result, visiting);
          p0Scrap.put(dependency.family().materialCode(), new PreparedFormulaInputs.ScrapPricePerKgExclTax(pair.financialPrice(), child.fact().evidence()));
          p1Scrap.put(dependency.family().materialCode(), new PreparedFormulaInputs.ScrapPricePerKgExclTax(pair.customerPrice(), child.fact().evidence()));
        }
        var prepared = new PreparedFormulaInputs(PreparedFormulaInputs.Catalog.fromScopedSql(definitions, placeholders, bindings),
            new PreparedFormulaInputs.Financial(factors, identities, factors == null ? Set.of() : factors.financial().factors().keySet(), privatePrices),
            new PreparedFormulaInputs.Scrap(references, p0Scrap));
        String month = calculation.path("pricingMonth").asText();
        LocalDate date = convert(calculation.path("priceAsOfDate"), LocalDate.class);
        financial = formula(rule, month, date, prepared);
        customer = copper == null ? financial : formula(rule, month, date,
            prepared.withCustomerCopper(copper, new PreparedFormulaInputs.Scrap(references, p1Scrap)));
      }
      default -> throw new IllegalArgumentException("Unknown monthly source: " + kind);
    }
    if (financial.compareTo(fact.price()) != 0 || customer.signum() < 0)
      throw new IllegalArgumentException("Monthly source replay differs from adopted P0 or has invalid P1: " + id);
    if ((fact.type() == PricingType.HOUSEHOLD_INQUIRY) != kind.equals("HOUSEHOLD_INQUIRY"))
      throw new IllegalArgumentException("Monthly type differs from source evidence");
    var evidence = new TreeMap<String, Object>();
    evidence.put("monthlyPriceId", id); evidence.put("sourceEvidenceHash", fact.evidence().sha256());
    evidence.put("customerCopperPerTon", copper); evidence.put("financialPrice", financial);
    evidence.put("customerPrice", customer); evidence.put("projectionVersion", "MONTHLY_CU_V1");
    var pair = new Pair(id, fact.evidence().sha256(), financial, customer, codec.unwrap(codec.encode(evidence)));
    result.put(id, pair); visiting.remove(id); return pair;
  }

  private BigDecimal formula(PriceLinkedItem rule, String month, LocalDate date, PreparedFormulaInputs prepared) {
    var value = legacyLinked.previewPrepared(rule, month, date, prepared);
    if (value.getResult() == null || value.getError() != null) throw new IllegalArgumentException("Captured formula cannot be replayed: " + value.getError());
    return value.getResult().setScale(6, RoundingMode.HALF_UP);
  }

  private BigDecimal range(MonthlyLegacyRangePriceSource.Source source, PriceFactorContext factors,
      MonthlyPriceRequest request, LocalDate firstAdoptionDate) {
    var rule = source.selectedRule();
    boolean dated = rule.getPricingMonth() != null && !rule.getPricingMonth().isBlank();
    if (!dated && firstAdoptionDate == null)
      throw new IllegalArgumentException("Undated legacy ranges require the immutable first-adoption date");
    String explicit = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
    var rows = source.bands().stream().filter(row -> Objects.equals(row.getFactorRuleId(), rule.getId()))
        .filter(row -> dated || row.getEffectiveFrom() == null || !row.getEffectiveFrom().isAfter(firstAdoptionDate))
        .filter(row -> request.family().unit().equals(row.getUnit())
            && (request.family().specification() == null || request.family().specification().equals(row.getSpecModel())))
        .filter(row -> explicit == null || explicit.equals(com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode())))
        .toList();
    if (rows.isEmpty()) throw new IllegalArgumentException("Captured legacy range has no applicable bands");
    var supplier = source.supplier();
    var selection = new SupplierPreferredPriceSelection<PriceRangeItem>(rows.getFirst(), supplier.message(), supplier.candidateSupplierCount(),
        supplier.supplierName(), supplier.supplierCode(), supplier.ratio(), supplier.ratioRecordId(), supplier.mode(), null, null);
    var value = factors.requiredPrice(source.factorBinding().canonicalKey(), FactorPriceUnit.require(rule.getFactorUnit()), true);
    var result = legacyRange.resolveFactorPrepared(rule, rows, value,
        java.time.YearMonth.parse(factors.months().requestedMonth()).atDay(1), selection);
    if (result.unitPrice() == null || result.hasBlockingFailure()) throw new IllegalArgumentException("Captured range has no valid customer band: " + result.remark());
    return result.unitPrice();
  }

  private static PriceFactorContext customer(PriceFactorContext financial, BigDecimal copper) {
    return copper == null ? financial.financialOnly() : financial.withCustomerCopperPerTon(copper);
  }
  private <T> T read(String json, Class<T> type) { return codec.decode(codec.wrap(json), type); }
  private <T> T convert(JsonNode node, Class<T> type) { return read(node.toString(), type); }
  private <T> List<T> list(JsonNode node, Class<T> type) {
    if (!node.isArray()) throw new IllegalArgumentException("Missing captured list of " + type.getSimpleName());
    var result = new ArrayList<T>(); node.forEach(value -> result.add(convert(value, type))); return result;
  }
}
