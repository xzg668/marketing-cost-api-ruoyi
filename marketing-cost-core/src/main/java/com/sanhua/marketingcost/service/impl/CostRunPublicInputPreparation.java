package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunFeeQuery;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.service.CostBusinessRuleProvider;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService;
import com.sanhua.marketingcost.service.NetLossRateQuery;
import com.sanhua.marketingcost.service.QuotePricingPeriod;
import com.sanhua.marketingcost.service.costing.CostRunFeeSources;
import com.sanhua.marketingcost.service.costing.CostRunManufacturingSources;
import com.sanhua.marketingcost.service.costing.CostRunPreparedInputs;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Validate each consumer first, then read its exact public inputs together. Does not compute costs. */
@Component
public final class CostRunPublicInputPreparation {
  public record Request(CostRunContext context, Set<String> materialCodes) {
    public Request {
      materialCodes = Collections.unmodifiableSet(new LinkedHashSet<>(materialCodes));
      if (materialCodes.isEmpty() || materialCodes.size() > 256
          || materialCodes.stream().anyMatch(code -> !StringUtils.hasText(code))) {
        throw new IllegalArgumentException("每个核算对象须提供1至256个实际成本料号");
      }
    }
  }

  private final CostRunCostItemServiceImpl costing;
  private final CostRunFeeSources fees;
  private final CostRunManufacturingSources manufacturing;
  private final EffectiveTechnicalDataQueryService technical;
  private final NetLossRateQuery netLoss;
  private final QuotePricingPeriod periods;

  public CostRunPublicInputPreparation(CostRunCostItemServiceImpl costing, CostRunFeeSources fees,
      CostRunManufacturingSources manufacturing, EffectiveTechnicalDataQueryService technical,
      NetLossRateQuery netLoss, QuotePricingPeriod periods) {
    this.costing = costing; this.fees = fees; this.manufacturing = manufacturing;
    this.technical = technical; this.netLoss = netLoss; this.periods = periods;
  }

  /** Keeps each consumer's selected source active only while its own approval is read. */
  @FunctionalInterface
  public interface TechnicalDataScope {
    com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput read(Request request,
        java.util.function.Supplier<com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput> query);
  }

  public List<CostRunPreparedInputs> prepare(List<Request> requests) {
    return prepare(requests, (request, query) -> query.get());
  }

  public List<CostRunPreparedInputs> prepare(List<Request> requests, TechnicalDataScope technicalScope) {
    var stable = List.copyOf(requests);
    if (stable.size() > 64) throw new IllegalArgumentException("公共输入每批最多64个核算对象");
    // Customer-specific approved data must be checked before sharing any public source.
    var dimensions = stable.stream().map(request -> costing.inputDimensions(request.context())).toList();
    for (var request : stable) {
      request.context().setEffectiveTechnicalData(technicalScope.read(request, () -> technical.resolve(
          request.context().getOaFormItemId(), request.context().getPricingMonth())));
    }
    var facts = new LinkedHashMap<Object, List<?>>();
    var materialQueries = new LinkedHashSet<CostRunFeeQuery.Material>();
    for (int i = 0; i < stable.size(); i++) {
      var request = stable.get(i); var scope = dimensions.get(i);
      materialQueries.add(new CostRunFeeQuery.Material(request.context().getProductCode().trim(), scope.organization()));
      request.materialCodes().forEach(code -> materialQueries.add(new CostRunFeeQuery.Material(code.trim(), scope.organization())));
    }
    load(facts, List.copyOf(materialQueries), fees::readMaterial);
    var materialFacts = new CostRunPreparedInputs.Catalog(facts);
    var departments = new ArrayList<CostRunFeeQuery.Department>();
    var manufacture = new ArrayList<CostRunFeeQuery.Manufacture>();
    var three = new ArrayList<CostRunFeeQuery.ThreeExpense>();
    var other = new ArrayList<CostRunFeeQuery.OtherExpense>();
    var properties = new ArrayList<CostRunFeeQuery.Property>();
    var rules = new ArrayList<CostRunFeeQuery.Rule>();
    var ruleMonths = new ArrayList<String>();
    var fallbackMonths = new LinkedHashMap<String, String>();
    int propertyYear = Year.now(CostPricingPeriodUtils.BUSINESS_ZONE).getValue();
    var wages = new ArrayList<CostRunManufacturingSources.Demand>();
    var losses = new ArrayList<NetLossRateQuery.Lookup>();
    for (int i = 0; i < stable.size(); i++) {
      var request = stable.get(i); var context = request.context(); var scope = dimensions.get(i);
      String product = context.getProductCode().trim();
      departments.addAll(costing.departmentRequests(product, scope, materialFacts));
      manufacture.addAll(costing.manufactureRequests(product, request.materialCodes(), scope, materialFacts));
      if (scope.threeExpense() != null) three.add(scope.threeExpense());
      other.add(new CostRunFeeQuery.OtherExpense(product));
      var master = materialFacts.one(new CostRunFeeQuery.Material(product, scope.organization()), MaterialMasterRaw.class);
      String propertyCode = com.sanhua.marketingcost.service.costing.ProductPropertyCoefficients
          .lookupCode(product, master == null ? null : master.getBareCode());
      properties.add(new CostRunFeeQuery.Property(propertyYear, propertyCode, scope.unit()));
      String ruleMonth = StringUtils.hasText(scope.accountingMonth())
          ? CostPricingPeriodUtils.normalizePricingMonth(scope.accountingMonth())
          : fallbackMonths.computeIfAbsent(scope.unit(), periods::currentMonth);
      ruleMonths.add(ruleMonth);
      for (String rule : List.of(CostBusinessRuleProvider.CMS_AUX_UPLIFT_RATE,
          CostBusinessRuleProvider.PACKAGE_COMPONENT_COEFFICIENT)) rules.add(new CostRunFeeQuery.Rule(rule, ruleMonth, scope.unit()));
      var approved = context.getEffectiveTechnicalData();
      wages.add(new CostRunManufacturingSources.Demand(scope.year(), scope.unit(), context.getPricingMonth(),
          request.materialCodes(), approved == null || !approved.salaryRequired(), approved == null || !approved.auxiliaryRequired()));
      losses.add(new NetLossRateQuery.Lookup(product, scope.model(), scope.organization(), scope.year(), scope.unit()));
    }
    load(facts, departments, fees::readDepartment);
    load(facts, manufacture, fees::readManufacture);
    load(facts, three, fees::readThreeExpense);
    load(facts, other, fees::readOtherExpense);
    load(facts, properties, fees::readProperty);
    load(facts, rules, fees::readRule);
    var partial = new CostRunPreparedInputs.Catalog(facts);
    var propertyRules = new ArrayList<CostRunFeeQuery.PropertyRule>();
    for (int i = 0; i < stable.size(); i++) {
      var context = stable.get(i).context(); var scope = dimensions.get(i);
      var raw = partial.one(new CostRunFeeQuery.Material(context.getProductCode().trim(), scope.organization()), MaterialMasterRaw.class);
      var property = partial.one(properties.get(i), ProductProperty.class);
      if (property != null && StringUtils.hasText(property.getProductAttr()) && property.getPropertyYear() != null
          && StringUtils.hasText(property.getBusinessUnitType())) {
        propertyRules.add(new CostRunFeeQuery.PropertyRule(property.getPropertyYear(), property.getProductAttr(), property.getBusinessUnitType()));
      } else if (property == null && raw != null && StringUtils.hasText(raw.getProductAttr())) {
        propertyRules.add(new CostRunFeeQuery.PropertyRule(propertyYear, raw.getProductAttr().trim(), scope.unit()));
      } else if (property == null && context.getEffectiveTechnicalData() != null
          && StringUtils.hasText(context.getEffectiveTechnicalData().productProperty())) {
        propertyRules.add(new CostRunFeeQuery.PropertyRule(propertyYear, context.getEffectiveTechnicalData().productProperty(), scope.unit()));
      }
    }
    load(facts, propertyRules, fees::readPropertyRule);
    var wageFacts = manufacturing.readBatch(wages);
    var lossFacts = netLoss.lookupBatch(losses, fees);
    var catalog = new CostRunPreparedInputs.Catalog(facts);
    var result = new ArrayList<CostRunPreparedInputs>();
    for (int i = 0; i < stable.size(); i++) {
      var request = stable.get(i);
      result.add(new CostRunPreparedInputs(request.context(), request.materialCodes(), wageFacts.get(i),
          catalog, lossFacts.get(i), propertyYear, ruleMonths.get(i)));
    }
    return List.copyOf(result);
  }

  private static <Q, T> void load(Map<Object, List<?>> facts, List<Q> requests,
      Function<List<Q>, List<List<T>>> read) {
    var distinct = List.copyOf(new LinkedHashSet<>(requests));
    for (int start = 0; start < distinct.size(); start += 4096) {
      var chunk = distinct.subList(start, Math.min(start + 4096, distinct.size()));
      var rows = read.apply(chunk);
      if (rows.size() != chunk.size()) throw new IllegalStateException("批量公共资料返回数量与请求不一致");
      for (int i = 0; i < chunk.size(); i++) facts.put(chunk.get(i), rows.get(i));
    }
  }
}
