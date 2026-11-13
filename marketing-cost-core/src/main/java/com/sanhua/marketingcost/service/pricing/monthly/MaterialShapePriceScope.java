package com.sanhua.marketingcost.service.pricing.monthly;

import java.util.*;
import java.util.function.Function;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Selection;

/** Explicit source eligibility captured with a price request, independent of source priority. */
public final class MaterialShapePriceScope {
  private MaterialShapePriceScope() {}
  private static final List<String> SOURCES = List.of("FIXED","LINKED","RANGE","TIER","ORDER",
      "INVOICE","INQUIRY","HOUSEHOLD_INQUIRY","SETTLE_FIXED","SETTLE_LINKED","SCRAP","MAKE");

  public static String of(String shape, Collection<String> sources, String revision) {
    if (!Set.of("PURCHASE","OUTSOURCE","MANUFACTURE","OUTSOURCE_WEIGHT").contains(shape)
        || revision == null || !revision.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("物料形态价格范围无效");
    int mask = 0;
    for (String source : sources) {
      int bit = SOURCES.indexOf(source);
      if (bit < 0) throw new IllegalArgumentException("未知价格来源：" + source);
      mask |= 1 << bit;
    }
    return shape + ":" + Integer.toHexString(mask) + ":" + revision;
  }

  public static String shape(String scope) { return scope == null ? null : parts(scope)[0]; }
  public static String pinned(String shape,String source,String revision) {
    return of(shape,List.of(source),revision)+":"+source;
  }
  public static String pinnedSource(String scope) {
    if(scope==null) return null;
    var fields=parts(scope);return fields.length==4?fields[3]:null;
  }
  public static boolean allows(String scope, String source) {
    if (scope == null) return true;
    int bit = SOURCES.indexOf(source);
    return bit >= 0 && (Integer.parseInt(parts(scope)[1],16) & (1 << bit)) != 0;
  }
  private static String[] parts(String scope) {
    String[] parts = scope.split(":",-1);
    if ((parts.length != 3 && parts.length != 4) || !parts[1].matches("[0-9a-f]{1,4}") || !parts[2].matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("物料形态价格范围损坏");
    if(parts.length==4 && (!SOURCES.contains(parts[3])
        || Integer.parseInt(parts[1],16)!=(1<<SOURCES.indexOf(parts[3]))))
      throw new IllegalArgumentException("选用价格源范围损坏");
    return parts;
  }

  public static Candidates read(String source, Set<MonthlyPriceRequest> requests,
      Function<Set<MonthlyPriceRequest>,Candidates> reader) {
    var permitted = new LinkedHashSet<MonthlyPriceRequest>();
    var problems = new LinkedHashMap<MonthlyPriceRequest,Problem>();
    for (var request : requests) {
      String scope = request.conditions().materialShapeScope();
      if (allows(scope,source)) permitted.add(request);
      else problems.put(request,new Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING,
          "料号"+request.materialCode()+"的"+source+"来源未标为本次选用形态"));
    }
    var values = new LinkedHashMap<MonthlyPriceRequest,Candidate>();
    if (!permitted.isEmpty()) {
      var found = reader.apply(permitted); problems.putAll(found.problems());
      found.values().forEach((request,candidate) -> {
        String scope = request.conditions().materialShapeScope();
        if (scope == null) values.put(request,candidate);
        else {
          var selected = candidate.selection();
          var coverage = selected.applicability().fragments().stream().map(fragment ->
              new MonthlyPriceCoverage(fragment.organizations(),fragment.quantities(),
                  fragment.conditions().forMaterialShape(scope),fragment.annualUsage())).toList();
          values.put(request,new Candidate(new Selection(selected.monthlyPriceId(),
              new MonthlyPriceApplicability(coverage),selected.fact()),candidate.householdSourceId()));
        }
      });
    }
    return new Candidates(values,problems);
  }
}
