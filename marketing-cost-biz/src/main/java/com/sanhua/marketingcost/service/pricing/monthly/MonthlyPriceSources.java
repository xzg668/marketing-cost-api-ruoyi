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

  public MonthlyPriceSources(MaterialPricePriorityService priorities,HouseholdMonthlyPriceSource household,
      MonthlyFixedPriceSource fixed,MonthlySettlementPriceSource settlement,MonthlyLegacyLinkedPriceSource linked,
      MonthlyOriginalLinkedPriceSource originalLinked,MonthlyLegacyRangePriceSource range,
      MonthlyOriginalRangePriceSource originalRange,MonthlyQuantityRangeSource quantities,
      MonthlyLegacyRangeSourceMapper rangeRows,MonthlySupplementalFixedPriceSource supplemental,MonthlyScrapPriceSource scrap,
      MonthlyOdsPriceSource ods) {
    this.priorities=priorities;this.household=household;this.fixed=fixed;this.settlement=settlement;
    this.linked=linked;this.originalLinked=originalLinked;this.range=range;this.originalRange=originalRange;
    this.quantities=quantities;this.rangeRows=rangeRows;this.supplemental=supplemental;this.scrap=scrap;
    this.ods=ods;
  }

  @Override public Candidates resolve(Context context,Set<MonthlyPriceRequest> requests,
      Function<Set<MonthlyPriceRequest>,Candidates> dependencies) {
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var pending=new LinkedHashSet<>(requests);
    var orders=orders(context,pending);
    var automatic=new LinkedHashSet<MonthlyPriceRequest>();pending.stream().filter(request->orders.get(request).size()>1).forEach(automatic::add);
    if(!automatic.isEmpty())accept(household.read(context,automatic),pending,values,problems);
    // Registered scrap retains its existing exclusive source contract. This resolver is
    // called only for missing monthly facts; registration cannot replace an adopted fact.
    automatic.retainAll(pending);
    if (!automatic.isEmpty()) {
      var registeredScrap = scrap.read(context, Set.copyOf(automatic));
      accept(registeredScrap,pending,values,problems);
      registeredScrap.problems().forEach((request,problem)->{
        problems.put(request,problem);pending.remove(request);
      });
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
      var found=ods.orders(context,Set.copyOf(pending));requireComplete(pending,found);
      accept(found,pending,values,problems);
    }
    if(!pending.isEmpty()) {
      var found=ods.tiers(context,Set.copyOf(pending));requireComplete(pending,found);
      accept(found,pending,values,problems);
    }
    if(!pending.isEmpty()) {
      var found=supplemental.read(context,Set.copyOf(pending));requireComplete(pending,found);
      accept(found,pending,values,problems);
      pending.forEach(request->problems.put(request,found.problems().get(request)));
    }
    return new Candidates(values,problems);
  }

  @Override public Candidates resolveScrap(Context context,Set<MonthlyPriceRequest> requests,
      Function<Set<MonthlyPriceRequest>,Candidates> dependencies) {
    var found = scrap.read(context,requests);
    var problems = new LinkedHashMap<>(found.problems());
    for (var request : requests) if (!found.values().containsKey(request))
      problems.putIfAbsent(request,new Problem(Failure.MISSING,"废料没有适用价格规则"));
    return new Candidates(found.values(),problems);
  }

  private Map<MonthlyPriceRequest,List<MaterialPricePriorityService.Entry>> orders(Context context,Set<MonthlyPriceRequest> requests) {
    var groups=new TreeMap<String,Map<String,List<MonthlyPriceRequest>>>();
    requests.forEach(request->groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>())
        .computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request));
    var result=new HashMap<MonthlyPriceRequest,List<MaterialPricePriorityService.Entry>>();
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
      var codes=new ArrayList<>(items.stream().map(request->request.family().materialCode()).distinct().toList());
      var found=new HashSet<String>();
      for(int start=0;start<codes.size();start+=256)found.addAll(rangeRows.factorMaterials(context.businessUnitType(),org,codes.subList(start,Math.min(start+256,codes.size()))));
      items.stream().filter(request->found.contains(request.family().materialCode())).forEach(factorRequests::add);
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
      var rest=next.apply(missing);requireComplete(missing,rest);missing.forEach(problems::remove);
      values.putAll(rest.values());problems.putAll(rest.problems());
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
    });
  }
  private static void requireComplete(Set<MonthlyPriceRequest> requests,Candidates found) {
    if(requests.stream().anyMatch(request->!found.values().containsKey(request) && !found.problems().containsKey(request)))
      throw new MonthlyPriceProblem(Failure.PERSISTENCE_INCOMPLETE,"Price source omitted requested material");
  }
}
