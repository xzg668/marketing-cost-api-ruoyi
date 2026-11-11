package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.mapper.monthly.MonthlyLegacyRangeSourceMapper;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Read-only routing: household, existing per-material price order, then supplemental fixed. */
@Component
public final class MonthlyPriceSources implements MonthlyPriceCandidateResolver {
  private final MaterialPricePriorityService priorities;
  private final HouseholdMonthlyPriceSource household;
  private final MonthlyFixedPriceSource fixed;
  private final MonthlySettlementPriceSource settlement;
  private final MonthlyLegacyLinkedPriceSource linked;
  private final MonthlyOriginalLinkedPriceSource originalLinked;
  private final MonthlyLegacyRangePriceSource range;
  private final MonthlyOriginalRangePriceSource originalRange;
  private final MonthlyQuantityRangeSource quantities;
  private final MonthlyLegacyRangeSourceMapper rangeRows;
  private final MonthlySupplementalFixedPriceSource supplemental;
  private final MonthlyScrapPriceSource scrap;
  private final MonthlyOdsPriceSource ods;
  private final MonthlyInvoicePriceSource invoices;
  private final MonthlyInquiryPriceSource inquiries;
  private final MonthlyRepriceSupplementalPriceSource historicalSupplemental;

  public MonthlyPriceSources(MaterialPricePriorityService priorities,HouseholdMonthlyPriceSource household,
      MonthlyFixedPriceSource fixed,MonthlySettlementPriceSource settlement,MonthlyLegacyLinkedPriceSource linked,
      MonthlyOriginalLinkedPriceSource originalLinked,MonthlyLegacyRangePriceSource range,
      MonthlyOriginalRangePriceSource originalRange,MonthlyQuantityRangeSource quantities,
      MonthlyLegacyRangeSourceMapper rangeRows,MonthlySupplementalFixedPriceSource supplemental,MonthlyScrapPriceSource scrap,
      MonthlyOdsPriceSource ods,MonthlyInvoicePriceSource invoices,MonthlyInquiryPriceSource inquiries,
      MonthlyRepriceSupplementalPriceSource historicalSupplemental) {
    this.priorities=priorities;this.household=household;this.fixed=fixed;this.settlement=settlement;
    this.linked=linked;this.originalLinked=originalLinked;this.range=range;this.originalRange=originalRange;
    this.quantities=quantities;this.rangeRows=rangeRows;this.supplemental=supplemental;this.scrap=scrap;
    this.ods=ods;
    this.invoices=invoices;
    this.inquiries=inquiries;
    this.historicalSupplemental=historicalSupplemental;
  }

  @Override public Candidates resolve(Context context,Set<MonthlyPriceRequest> requests,
      Function<Set<MonthlyPriceRequest>,Candidates> dependencies) {
    var orders=orders(context,requests);
    var local=resolveOrganization(context,requests,dependencies,orders);
    return withOtherOrganization(requests,local,alternatives->{
      var alternativeOrders=new LinkedHashMap<MonthlyPriceRequest,List<MaterialPricePriorityService.Entry>>();
      for(var request:requests)alternativeOrders.put(otherOrganization(request),orders.get(request));
      return resolveOrganization(context,alternatives,dependencies,alternativeOrders);
    });
  }

  /** Finish every source in the requested organization before relaxing that one condition. */
  private Candidates resolveOrganization(Context context,Set<MonthlyPriceRequest> requests,
      Function<Set<MonthlyPriceRequest>,Candidates> dependencies,
      Map<MonthlyPriceRequest,List<MaterialPricePriorityService.Entry>> orders) {
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var pending=new LinkedHashSet<>(requests);
    var automatic=new LinkedHashSet<MonthlyPriceRequest>();pending.stream().filter(request->orders.get(request).size()>1).forEach(automatic::add);
    if(!automatic.isEmpty())accept(household.read(context,automatic),pending,values,problems);
    // Existing scrap rules retain priority; a genuinely missing scrap price may use inquiry.
    automatic.retainAll(pending);
    if (!automatic.isEmpty()) {
      var registeredScrap = scrap.read(context, Set.copyOf(automatic));
      accept(registeredScrap,pending,values,problems);
      var missingScrap=new LinkedHashSet<MonthlyPriceRequest>();
      registeredScrap.problems().forEach((request,problem)->{if(problem.reason()==Failure.MISSING)missingScrap.add(request);});
      if(!missingScrap.isEmpty()) {
        var inquiry=inquiries.read(context,missingScrap);requireComplete(missingScrap,inquiry);
        accept(inquiry,pending,values,problems);
        for(var request:missingScrap)if(pending.remove(request))problems.put(request,registeredScrap.problems().get(request));
      }
    }
    for(int rank=0;rank<MaterialPriceSourceType.DEFAULT_ORDER.size() && !pending.isEmpty();rank++) {
      var groups=new EnumMap<MaterialPriceSourceType,Set<MonthlyPriceRequest>>(MaterialPriceSourceType.class);
      for(var request:pending)groups.computeIfAbsent(orders.get(request).get(rank).type(),ignored->new LinkedHashSet<>()).add(request);
      for(var group:groups.entrySet()) {
        Candidates found=switch(group.getKey()) {
          case FIXED -> fixed.read(context,group.getValue());
          case SETTLE_FIXED,SETTLE_LINKED -> {
            var types=new LinkedHashMap<MonthlyPriceRequest,SettlementPriceType>();
            group.getValue().forEach(request->types.put(request,SettlementPriceType.valueOf(group.getKey().name())));
            yield settlement.read(context,types);
          }
          case LINKED -> chain(group.getValue(),batch->linked.read(context,batch,dependencies),batch->originalLinked.read(context,batch));
          case RANGE -> range(context,group.getValue());
        };
        requireComplete(group.getValue(),found);
        accept(found,pending,values,problems);
        // A finance designation is exclusive: missing that type cannot activate a different type.
        for(var request:group.getValue())if(orders.get(request).size()==1 && pending.remove(request))
          problems.put(request,new Problem(Failure.MISSING,"财务指定的"+group.getKey().label()+"没有适用价格"));
      }
    }
    if(!pending.isEmpty()) {
      var found=ods.tiers(context,Set.copyOf(pending));requireComplete(pending,found);
      accept(found,pending,values,problems);
    }
    if(!pending.isEmpty()) {
      var found=invoices.read(context,Set.copyOf(pending));requireComplete(pending,found);
      accept(found,pending,values,problems);
    }
    if(!pending.isEmpty()) {
      var found=ods.orders(context,Set.copyOf(pending));requireComplete(pending,found);
      accept(found,pending,values,problems);
    }
    if(!pending.isEmpty()) {
      var found=inquiries.read(context,Set.copyOf(pending));requireComplete(pending,found);
      accept(found,pending,values,problems);
    }
    if(!pending.isEmpty()) {
      var currentSupplemental=supplemental.read(context,Set.copyOf(pending));requireComplete(pending,currentSupplemental);
      var found=historicalSupplemental.fill(context,currentSupplemental);
      accept(found,pending,values,problems);
      // A last, empty supplemental lookup says nothing about earlier source restrictions.
      pending.forEach(request->problems.computeIfAbsent(request,ignored->
          new Problem(Failure.MISSING,"未取得可用价格，请核对各价格来源")));
    }
    return new Candidates(values,problems);
  }

  private Candidates withOtherOrganization(Set<MonthlyPriceRequest> requests,Candidates local,
      Function<Set<MonthlyPriceRequest>,Candidates> lookup) {
    var aliases=new LinkedHashMap<MonthlyPriceRequest,MonthlyPriceRequest>();
    var localResults=new HashSet<MonthlyPriceRequest>();
    for(var request:requests)if(local.values().containsKey(request)
        || local.problems().containsKey(request) && local.problems().get(request).reason()!=Failure.MISSING)
      localResults.add(taxGroup(request));
    for(var request:requests) {
      var problem=local.problems().get(request);
      if(problem==null || problem.reason()!=Failure.MISSING)continue;
      // A valid local tax basis or actual public-source blocker retains priority.
      // Unsubmitted technical supplements are ignored by their own source adapter.
      if(!localResults.contains(taxGroup(request)))aliases.put(request,otherOrganization(request));
    }
    if(aliases.isEmpty())return local;
    var foreign=lookup.apply(new LinkedHashSet<>(aliases.values()));
    requireComplete(new LinkedHashSet<>(aliases.values()),foreign);
    var values=new LinkedHashMap<>(local.values());var problems=new LinkedHashMap<>(local.problems());
    var dependencies=new HashSet<MonthlyPriceRequest>();
    foreign.values().values().forEach(candidate->dependencies.addAll(candidate.selection().fact().dependencies()));
    // Source callbacks may return a complete dependency graph. Keep its actual request identities.
    foreign.values().forEach((request,candidate)->{
      if(!aliases.containsValue(request) || dependencies.contains(request)) {
        var previous=values.putIfAbsent(request,candidate);
        if(previous!=null && !previous.equals(candidate))throw new MonthlyPriceProblem(Failure.REPLAN_REQUIRED,
            "跨组织取价的依赖依据发生变化，请重新取价");
      }
    });
    aliases.forEach((request,sourceRequest)->{
      var candidate=foreign.values().get(sourceRequest);
      if(candidate!=null) {
        var selected=candidate.selection();
        if(selected.monthlyPriceId()!=null)throw new MonthlyPriceProblem(Failure.PERSISTENCE_INCOMPLETE,
            "跨组织来源不能替换已采用月价身份");
        var fragments=selected.applicability().fragments().stream()
            .filter(fragment->fragment.matches(sourceRequest))
            .map(fragment->new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),fragment.quantities(),
                ConditionDomain.exact(request.conditions()),fragment.annualUsage())).toList();
        // The consumer keeps its BOM organization; the immutable source retains its real org,
        // selected supplier, formula, factors and amount, without rewriting source records.
        values.put(request,new Candidate(new MonthlyAdoptionPlan.Selection(null,
            new MonthlyPriceApplicability(fragments),selected.fact()),candidate.householdSourceId()));
        problems.remove(request);
      } else if(foreign.problems().get(sourceRequest).reason()!=Failure.MISSING) {
        problems.put(request,foreign.problems().get(sourceRequest));
      }
    });
    return new Candidates(values,problems);
  }

  private static MonthlyPriceRequest otherOrganization(MonthlyPriceRequest request) {
    String organization="210".equals(request.priceOrgCode())?"220":"210";
    return new MonthlyPriceRequest(request.family(),organization,
        "220".equals(organization)?request.conditions():NonCuConditions.empty(),
        request.priceBasisQuantity(),request.annualUsagePieces());
  }

  private static MonthlyPriceRequest taxGroup(MonthlyPriceRequest request) {
    var family=request.family();
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(family.month(),family.businessUnitType(),
        family.materialCode(),family.specification(),family.unit(),family.currency(),TaxBasis.EXCLUDED,
        family.requestedSupplierCode(),family.specificationOnly()),request.priceOrgCode(),request.conditions(),
        request.priceBasisQuantity(),request.annualUsagePieces());
  }

  @Override public Candidates resolveScrap(Context context,Set<MonthlyPriceRequest> requests,
      Function<Set<MonthlyPriceRequest>,Candidates> dependencies) {
    var local=resolveScrapOrganization(context,requests);
    return withOtherOrganization(requests,local,alternatives->resolveScrapOrganization(context,alternatives));
  }

  private Candidates resolveScrapOrganization(Context context,Set<MonthlyPriceRequest> requests) {
    var found = scrap.read(context,requests);
    var values = new LinkedHashMap<>(found.values());
    var problems = new LinkedHashMap<>(found.problems());
    var missing = new LinkedHashSet<MonthlyPriceRequest>();
    for (var request : requests) if (!found.values().containsKey(request))
      if(!problems.containsKey(request)||problems.get(request).reason()==Failure.MISSING)missing.add(request);
    if(!missing.isEmpty()) {
      var inquiry=inquiries.read(context,missing);requireComplete(missing,inquiry);
      for(var request:missing)problems.remove(request);
      values.putAll(inquiry.values());problems.putAll(inquiry.problems());
    }
    return new Candidates(values,problems);
  }

  private Map<MonthlyPriceRequest,List<MaterialPricePriorityService.Entry>> orders(Context context,Set<MonthlyPriceRequest> requests) {
    var groups=new TreeMap<String,Map<String,List<MonthlyPriceRequest>>>();
    requests.stream().filter(request->!request.materialCode().isBlank()).forEach(request->groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>())
        .computeIfAbsent(request.materialCode(),ignored->new ArrayList<>()).add(request));
    var result=new HashMap<MonthlyPriceRequest,List<MaterialPricePriorityService.Entry>>();
    requests.stream().filter(request->request.materialCode().isBlank()).forEach(request->result.put(request,
        MaterialPriceSourceType.DEFAULT_ORDER.stream().map(type->new MaterialPricePriorityService.Entry(type,type.getDefaultPriority())).toList()));
    groups.forEach((org,codes)->{
      var all=new ArrayList<>(codes.keySet());
      for(int start=0;start<all.size();start+=256) {
        var names=new LinkedHashMap<String,String>();all.subList(start,Math.min(start+256,all.size())).forEach(code->names.put(code,code));
        var scope=new MaterialPricePriorityService.Scope(context.businessUnitType(),context.month().toString(),org,names.keySet().iterator().next());
        priorities.readBatch(scope,names,false).forEach((code,configuration)->codes.get(code).forEach(request->result.put(request,
            configuration.manual()?List.of(configuration.order().getFirst()):configuration.order())));
      }
    });
    return result;
  }

  private Candidates range(Context context,Set<MonthlyPriceRequest> requests) {
    var factorRequests=new LinkedHashSet<MonthlyPriceRequest>();
    var groups=new TreeMap<String,List<MonthlyPriceRequest>>();requests.forEach(request->groups.computeIfAbsent(request.priceOrgCode(),ignored->new ArrayList<>()).add(request));
    groups.forEach((org,items)->{
      var codes=new ArrayList<>(items.stream().map(request->request.materialCode()).distinct().toList());
      var found=new HashSet<String>();
      for(int start=0;start<codes.size();start+=256)found.addAll(rangeRows.factorMaterials(context.businessUnitType(),org,codes.subList(start,Math.min(start+256,codes.size()))));
      items.stream().filter(request->found.contains(request.materialCode())).forEach(factorRequests::add);
    });
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    if(!factorRequests.isEmpty()) {
      var found=chain(factorRequests,batch->range.read(context,batch),batch->originalRange.read(context,batch));
      values.putAll(found.values());problems.putAll(found.problems());
    }
    var qty=new LinkedHashSet<>(requests);qty.removeAll(factorRequests);
    if(!qty.isEmpty()){var found=quantities.read(context,qty);values.putAll(found.values());problems.putAll(found.problems());}
    return new Candidates(values,problems);
  }

  private static Candidates chain(Set<MonthlyPriceRequest> requests,Function<Set<MonthlyPriceRequest>,Candidates> first,
      Function<Set<MonthlyPriceRequest>,Candidates> next) {
    var found=first.apply(requests);requireComplete(requests,found);
    var values=new LinkedHashMap<>(found.values());var problems=new LinkedHashMap<>(found.problems());
    var missing=new LinkedHashSet<MonthlyPriceRequest>();
    requests.forEach(request->{var issue=problems.get(request);if(issue!=null && issue.reason()==Failure.MISSING)missing.add(request);});
    if(!missing.isEmpty()) {
      var rest=next.apply(missing);requireComplete(missing,rest);
      rest.values().forEach((request,value)->{values.put(request,value);problems.remove(request);});
      rest.problems().forEach((request,problem)->{
        var previous=problems.get(request);
        if(problem.reason()!=Failure.MISSING || !hasRestriction(previous))problems.put(request,problem);
      });
    }
    return new Candidates(values,problems);
  }

  private static void accept(Candidates found,Set<MonthlyPriceRequest> pending,
      Map<MonthlyPriceRequest,Candidate> values,Map<MonthlyPriceRequest,Problem> problems) {
    found.values().forEach((request,value)->{
      var previous=values.putIfAbsent(request,value);
      if(previous!=null && !previous.equals(value))throw new MonthlyPriceProblem(Failure.REPLAN_REQUIRED,"Price dependency changed between source batches");
      pending.remove(request);problems.remove(request);
    });
    found.problems().forEach((request,problem)->{
      if(problem.reason()!=Failure.MISSING){problems.put(request,problem);pending.remove(request);}
      else if(hasRestriction(problem))problems.putIfAbsent(request,problem);
    });
  }
  private static boolean hasRestriction(Problem problem) {
    if(problem==null || problem.message()==null)return false;
    String message=problem.message();
    return java.util.stream.Stream.of("主供","指定供应商","币种","税价口径","含税口径","规格不匹配",
        "数量未命中","没有适用档位","未发布","未生效","尚未生效").anyMatch(message::contains);
  }
  private static void requireComplete(Set<MonthlyPriceRequest> requests,Candidates found) {
    if(requests.stream().anyMatch(request->!found.values().containsKey(request) && !found.problems().containsKey(request)))
      throw new MonthlyPriceProblem(Failure.PERSISTENCE_INCOMPLETE,"Price source omitted requested material");
  }
}
