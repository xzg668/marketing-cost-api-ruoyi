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
    return preview(candidates, customerCopperPerTon, null, sourceDates);
  }

  public Map<MonthlyPriceRequest, PreviewPair> preview(Map<MonthlyPriceRequest, Selection> candidates,
      BigDecimal customerCopperPerTon, BigDecimal customerZincPerTon, Map<MonthlyPriceRequest, LocalDate> sourceDates) {
    return previewMaterials(candidates, CustomerMaterialPrices.copperAndZinc(customerCopperPerTon, customerZincPerTon), sourceDates);
  }

  public Map<MonthlyPriceRequest, PreviewPair> previewMaterials(Map<MonthlyPriceRequest, Selection> candidates,
      CustomerMaterialPrices customerPrices, Map<MonthlyPriceRequest, LocalDate> sourceDates) {
    Objects.requireNonNull(customerPrices);
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
    var publications = new HashMap<String, FactorPriceSnapshot>();
    for (long key : prices.keySet())
      project(key, customerPrices, prices, candidates, keys, dates, projected, visiting, publications);
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
  private final com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader finance;

  public MonthlyPriceScenarios(MonthlyPriceDocumentCodec codec, OriginalLinkedRuleCalculator linked,
      OriginalRangeRuleCalculator originalRange, OriginalRangeRuleSnapshot rangeSnapshots,
      RangePriceResolver legacyRange, PriceLinkedFormulaPreviewServiceImpl legacyLinked,
      SettlementPriceCalculator settlement, ScrapPriceCalculator scrap) {
    this(codec, linked, originalRange, rangeSnapshots, legacyRange, legacyLinked, settlement, scrap, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public MonthlyPriceScenarios(MonthlyPriceDocumentCodec codec, OriginalLinkedRuleCalculator linked,
      OriginalRangeRuleCalculator originalRange, OriginalRangeRuleSnapshot rangeSnapshots,
      RangePriceResolver legacyRange, PriceLinkedFormulaPreviewServiceImpl legacyLinked,
      SettlementPriceCalculator settlement, ScrapPriceCalculator scrap,
      com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader finance) {
    this.finance = finance;
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
    return calculate(requests, customerCopperPerTon, null, firstAdoptionDates);
  }

  public Map<Long, Pair> calculate(Map<MonthlyPriceRequest, Selection> requests, BigDecimal customerCopperPerTon,
      BigDecimal customerZincPerTon, Map<Long, LocalDate> firstAdoptionDates) {
    return calculateMaterials(requests, CustomerMaterialPrices.copperAndZinc(customerCopperPerTon, customerZincPerTon), firstAdoptionDates);
  }

  public Map<Long, Pair> calculateMaterials(Map<MonthlyPriceRequest, Selection> requests,
      CustomerMaterialPrices customerPrices, Map<Long, LocalDate> firstAdoptionDates) {
    Objects.requireNonNull(customerPrices);
    var byId = new LinkedHashMap<Long, Selection>();
    for (var price : requests.values()) {
      if (price.monthlyPriceId() == null || price.monthlyPriceId() <= 0)
        throw new IllegalArgumentException("Scenarios require adopted prices");
      var previous = byId.putIfAbsent(price.monthlyPriceId(), price);
      if (previous != null && !previous.equals(price)) throw new IllegalArgumentException("Conflicting monthly price identity");
    }
    var result = new LinkedHashMap<Long, Pair>();
    var visiting = new HashSet<Long>();
    var publications = new HashMap<String, FactorPriceSnapshot>();
    var keys = new HashMap<MonthlyPriceRequest, Long>();
    requests.forEach((request, selected) -> keys.put(request, selected.monthlyPriceId()));
    for (long id : byId.keySet()) project(id, customerPrices, byId, requests, keys, firstAdoptionDates, result, visiting, publications);
    return Map.copyOf(result);
  }

  private Pair project(long id, CustomerMaterialPrices customerPrices, Map<Long, Selection> prices, Map<MonthlyPriceRequest, Selection> requests,
      Map<MonthlyPriceRequest, Long> keys, Map<Long, LocalDate> firstAdoptionDates,
      Map<Long, Pair> result, Set<Long> visiting, Map<String, FactorPriceSnapshot> publications) {
    if (result.containsKey(id)) return result.get(id);
    if (!visiting.add(id)) throw new IllegalArgumentException("Cyclic monthly price evidence");
    var selected = Objects.requireNonNull(prices.get(id), "Missing adopted dependency");
    var fact = selected.fact();
    var root = read(fact.evidence().json(), JsonNode.class);
    if (root.path("snapshotSchema").asInt() != 1) throw new IllegalArgumentException("Unknown price evidence version");
    String kind = root.path("sourceKind").asText();
    BigDecimal financial;
    BigDecimal customer;
    HouseholdMetalCalculation.Trace householdCalculation=null;
    var customerContexts = new ArrayList<PriceFactorContext>();
    switch (kind) {
      case "HOUSEHOLD_INQUIRY" -> {
        var snapshot = read(fact.evidence().json(), HouseholdMonthlyPriceSource.Snapshot.class);
        var source = snapshot.source().evidence().source();
        financial = new BigDecimal(source.unitPrice()); customer = financial;
        if(snapshot.metalInputs()!=null) {
          var metal = snapshot.metalInputs();
          var projected = customerContext(metal.financial(), customerPrices, publications, customerContexts);
          householdCalculation=HouseholdMetalCalculation.calculate(financial,
              new HouseholdMetalCalculation.Inputs(metal.copperGrams(), metal.zincGrams(),
                  projected == null ? null : projected.financialOnly()),customerPrices);
          customer=householdCalculation.calculatedW();
        }
        if (fact.householdRate() == null || fact.householdRate().compareTo(new BigDecimal(source.householdRate())) != 0)
          throw new IllegalArgumentException("Adopted household H differs from source evidence");
      }
      case "PURCHASE_FIXED", "TECH_FIXED" -> {
        financial = new BigDecimal(root.path("source").path("selected").path("fixedPrice").asText()); customer = financial;
      }
      case "ODS_ORDER", "ODS_TIER" -> {
        financial = new BigDecimal(root.path("source").path("selected").path("price").asText()); customer = financial;
      }
      case "INVOICE" -> {
        financial = new BigDecimal(root.path("source").path("selected").path("unit_price").asText()); customer = financial;
      }
      case "INQUIRY" -> {
        var source = root.path("source").path("selected");
        financial = new BigDecimal(source.path("price").asText()); customer = financial;
        var calculation = source.path("calculation");
        if ("联动价".equals(calculation.path("priceType").asText())) {
          var inputs = new java.util.LinkedHashMap<String,BigDecimal>();
          calculation.path("inputs").fields().forEachRemaining(entry -> inputs.put(entry.getKey(),new BigDecimal(entry.getValue().asText())));
          financial = com.sanhua.marketingcost.formula.registry.ExpressionEvaluator.evaluate(calculation.path("expression").asText(),inputs).setScale(18,RoundingMode.HALF_UP);
          FactorPriceSnapshot inquiryBase = calculation.path("financial").isObject()
              ? convert(calculation.path("financial"), FactorPriceSnapshot.class) : null;
          if (inquiryBase == null && finance != null && customerPrices.copper() != null
              && calculation.path("financeBatchId").canConvertToLong()) {
            inquiryBase = finance.readCaptured(calculation.path("financeBatchId").longValue(),
                calculation.path("financeRevision").intValue(), selectedRequest(id, requests, keys).family().businessUnitType(),
                "210", calculation.path("financeMonth").asText(), calculation.path("financeScheme").asText(), FactorPriceScope.Condition.GENERAL);
          }
          var inquiryContext = inquiryBase == null ? null : customerContext(new PriceFactorContext(
              selectedRequest(id, requests, keys).family().materialCode(),
              new PriceMonthSelection(selectedRequest(id, requests, keys).family().month().toString(), inquiryBase.scope().priceMonth()),
              inquiryBase, null), customerPrices, publications, customerContexts);
          calculation.path("factors").fields().forEachRemaining(entry -> {
            String factor = entry.getValue().asText();
            BigDecimal customerMetal = customerPrices.pricePerTon(factor);
            if (inquiryContext != null) {
              inputs.put(entry.getKey(), inquiryContext.requiredPrice(factor,
                  FactorPriceUnit.require(calculation.path("financeFactors").path(factor).path("unit").asText()), true));
            } else if (customerMetal != null) {
              String unit = calculation.path("financeFactors").path(factor).path("unit").asText();
              inputs.put(entry.getKey(),com.sanhua.marketingcost.service.pricing.FactorPriceUnit.CNY_TON.convert(customerMetal,
                  com.sanhua.marketingcost.service.pricing.FactorPriceUnit.require(unit)));
            }
          });
          customer = com.sanhua.marketingcost.formula.registry.ExpressionEvaluator.evaluate(calculation.path("expression").asText(),inputs).setScale(18,RoundingMode.HALF_UP);
        }
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
        customer = linked.calculate(saved.linkedRule(), customerContext(context, customerPrices, publications, customerContexts)).priceExcludingTax().setScale(18, RoundingMode.HALF_UP);
      }
      case "RANGE_FACTOR" -> {
        var source = read(fact.evidence().json(), MonthlyOriginalRangePriceSource.Snapshot.class).source();
        var saved = read(source.calculationJson(), OriginalRangePriceRuntime.Calculation.class);
        var rule = rangeSnapshots.read(source.selected()).rule();
        financial = originalRange.calculate(rule, saved.context().financialOnly()).priceExcludingTax().setScale(18, RoundingMode.HALF_UP);
        customer = originalRange.calculate(rule, customerContext(saved.context(), customerPrices, publications, customerContexts)).priceExcludingTax().setScale(18, RoundingMode.HALF_UP);
      }
      case "RANGE_LEGACY_FACTOR" -> {
        var source = read(fact.evidence().json(), MonthlyLegacyRangePriceSource.Snapshot.class).source();
        var request = requests.entrySet().stream().filter(entry -> keys.get(entry.getKey()) == id)
            .map(Map.Entry::getKey).findFirst().orElseThrow();
        LocalDate adoptionDate = firstAdoptionDates.get(id);
        financial = range(source, source.financialInputs(), request, adoptionDate);
        customer = range(source, customerContext(source.financialInputs(), customerPrices, publications, customerContexts), request, adoptionDate);
      }
      case "SCRAP_PRICE" -> {
        var snapshot = read(fact.evidence().json(), MonthlyScrapPriceSource.Snapshot.class);
        var source = snapshot.source();
        var captured = source.candidates().stream().filter(value -> Objects.equals(value.original().getId(), source.selected().getId())).findFirst().orElseThrow();
        var row = source.selected();
        row.setPriceMonth(snapshot.requestedMonth());
        financial = scrap.calculate(scrap.prepare(row, captured.input().factors()));
        customer = scrap.calculate(scrap.prepare(row, captured.input().factors() == null ? null
            : customerContext(captured.input().factors(), customerPrices, publications, customerContexts)));
      }
      case "SETTLE_FIXED", "SETTLE_LINKED" -> {
        var source = root.path("source");
        var capturedRule = (com.fasterxml.jackson.databind.node.ObjectNode) source.path("rule").deepCopy();
        var sourceRows = capturedRule.remove("sourceRows");
        if (sourceRows != null) capturedRule.put("sourceRowsJson", sourceRows.toString());
        var rule = convert(capturedRule, SettlementPriceRecord.class);
        var base = source.path("base").isNull() ? null : convert(source.path("base"), SettlementBasePrice.class);
        financial = settlement.calculate(rule, base).unitPrice();
        var copper = customerPrices.copper(); var zinc = customerPrices.zinc();
        if (base != null && base.financial() != null) {
          var ctx = customerContext(new PriceFactorContext(rule.getMaterialCode(),
              new PriceMonthSelection(base.priceMonth(), base.priceMonth()), base.financial(), null),
              customerPrices, publications, customerContexts);
          copper = ctx.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_TON, true);
          zinc = ctx.requiredPrice(PriceFactorContext.ZINC, FactorPriceUnit.CNY_TON, true);
        }
        var projected = base == null || (copper == null && zinc == null) ? base : new SettlementBasePrice(base.id(), base.businessUnitType(),
            base.priceMonth(), base.revisionNo(), base.active(), copper == null ? base.copperPrice() : copper.movePointLeft(3),
            zinc == null ? base.zincPrice() : zinc.movePointLeft(3), base.unit(), base.taxBasis(), base.sourceKind(), base.sourceFileSha256(),
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
              customerPrices, prices, requests, keys, firstAdoptionDates, result, visiting, publications);
          p0Scrap.put(dependency.family().materialCode(), new PreparedFormulaInputs.ScrapPricePerKgExclTax(pair.financialPrice(), child.fact().evidence()));
          p1Scrap.put(dependency.family().materialCode(), new PreparedFormulaInputs.ScrapPricePerKgExclTax(pair.customerPrice(), child.fact().evidence()));
        }
        var prepared = new PreparedFormulaInputs(PreparedFormulaInputs.Catalog.fromScopedSql(definitions, placeholders, bindings),
            new PreparedFormulaInputs.Financial(factors, identities, factors == null ? Set.of() : factors.financial().factors().keySet(), privatePrices),
            new PreparedFormulaInputs.Scrap(references, p0Scrap));
        String month = calculation.path("pricingMonth").asText();
        LocalDate date = convert(calculation.path("priceAsOfDate"), LocalDate.class);
        financial = formula(rule, month, date, prepared);
        customer = customerPrices.isEmpty() ? financial : formula(rule, month, date,
            prepared.withCustomerContext(customerContext(factors, customerPrices, publications, customerContexts),
                new PreparedFormulaInputs.Scrap(references, p1Scrap)));
      }
      default -> throw new IllegalArgumentException("Unknown monthly source: " + kind);
    }
    if (financial.compareTo(fact.price()) != 0 || customer.signum() < 0)
      throw new IllegalArgumentException("Monthly source replay differs from adopted P0 or has invalid P1: " + id);
    if ((fact.type() == PricingType.HOUSEHOLD_INQUIRY) != kind.equals("HOUSEHOLD_INQUIRY"))
      throw new IllegalArgumentException("Monthly type differs from source evidence");
    var evidence = new TreeMap<String, Object>();
    evidence.put("monthlyPriceId", id); evidence.put("sourceEvidenceHash", fact.evidence().sha256());
    evidence.put("customerCopperPerTon", customerPrices.copper()); evidence.put("financialPrice", financial);
    evidence.put("customerZincPerTon", customerPrices.zinc());
    evidence.put("customerMaterialPrices", customerPrices);
    if (!customerContexts.isEmpty()) evidence.put("customerFactorContexts", customerContexts);
    if(householdCalculation!=null) {
      evidence.put("householdCalculation",householdCalculation);
      evidence.put("originalW",root.path("source").path("evidence").path("source").path("financialReference").path("W"));
      evidence.put("inquirySourceMonth",root.path("source").path("evidence").path("sourceMonth").asText());
      if(root.path("match").isObject())evidence.put("householdMatch",root.path("match"));
    }
    evidence.put("customerPrice", customer); evidence.put("projectionVersion", CustomerMaterialPrices.CALCULATION_VERSION);
    var pair = new Pair(id, fact.evidence().sha256(), financial, customer, codec.unwrap(codec.encode(evidence)));
    result.put(id, pair); visiting.remove(id); return pair;
  }

  private MonthlyPriceRequest selectedRequest(long id, Map<MonthlyPriceRequest, Selection> requests,
      Map<MonthlyPriceRequest, Long> keys) {
    return requests.keySet().stream().filter(request -> Objects.equals(keys.get(request), id)).findFirst().orElseThrow();
  }

  private PriceFactorContext customerContext(PriceFactorContext base, CustomerMaterialPrices customer,
      Map<String, FactorPriceSnapshot> publications, List<PriceFactorContext> traces) {
    if (base == null) return null;
    var captured = base.financial();
    if (finance != null && customer.copper() != null && captured.cuBands().isEmpty()
        && "210".equals(captured.scope().priceOrgCode())) {
      String key = captured.batchId() + ":" + captured.revision() + ":" + captured.scope();
      var full = publications.computeIfAbsent(key, ignored -> finance.readCaptured(captured.batchId(), captured.revision(),
          captured.scope().businessUnitType(), captured.scope().priceOrgCode(), captured.scope().priceMonth(),
          captured.scope().schemeCode(), captured.scope().condition()));
      for (var entry : captured.factors().entrySet()) {
        var original = full.required(entry.getKey()); var saved = entry.getValue();
        if (!Objects.equals(original.monthlyPriceId(), saved.monthlyPriceId()) || original.price().compareTo(saved.price()) != 0
            || !original.unit().equals(saved.unit()) || original.taxIncluded() != saved.taxIncluded())
          throw new IllegalArgumentException("保存财务因素与原发布不一致：" + entry.getKey());
      }
      base = new PriceFactorContext(base.materialCode(), base.months(), captured.withCuBands(full.cuBands()), null, base.boardInputs());
    }
    var result = base.withCustomerMaterials(customer);
    if (result.customerBand() != null) traces.add(result.evidenceForFactors(base.financial().factors().keySet()));
    return result;
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

  private <T> T read(String json, Class<T> type) { return codec.decode(codec.wrap(json), type); }
  private <T> T convert(JsonNode node, Class<T> type) { return read(node.toString(), type); }
  private <T> List<T> list(JsonNode node, Class<T> type) {
    if (!node.isArray()) throw new IllegalArgumentException("Missing captured list of " + type.getSimpleName());
    var result = new ArrayList<T>(); node.forEach(value -> result.add(convert(value, type))); return result;
  }
}
