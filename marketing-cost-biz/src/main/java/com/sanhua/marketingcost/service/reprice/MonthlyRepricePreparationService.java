package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.service.pricing.SupplierPriceReview;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.Context;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;

/** Inspect the same saved facts and source rules used by costing, without adoption or generation. */
@Service
public class MonthlyRepricePreparationService {
  public record Line(MonthlyPriceRequest request, boolean dependency, String status,
      MonthlyAdoptionPlan.Selection price, String reason, String message) {}
  public record Preview(String businessUnitType, String pricingMonth, boolean ready,
      List<Line> lines, List<SupplierPriceReview> supplierReviews) {}

  private final MonthlyMaterialPrices prices;

  public MonthlyRepricePreparationService(MonthlyMaterialPrices prices) {
    this.prices = prices;
  }

  public Preview preview(Context context, List<MonthlyPriceRequest> materials) {
    Objects.requireNonNull(context, "目标月取价上下文必填");
    if (!context.month().toString().matches("[0-9]{4}-[0-9]{2}")
        || !YearMonth.from(context.priceAsOfTime()).equals(context.month()))
      throw new IllegalArgumentException("取价时点必须属于目标核算月份");
    if (materials == null || materials.isEmpty() || materials.size() > 256)
      throw new IllegalArgumentException("每次检查须包含1至256条实际计价物料");
    var requests = new LinkedHashSet<MonthlyPriceRequest>();
    for (var request : materials) {
      if (request == null || !context.month().equals(request.family().month())
          || !context.businessUnitType().equals(request.family().businessUnitType())
          || !context.consumerOrg().equals(request.priceOrgCode()))
        throw new IllegalArgumentException("物料月份、业务单元或取价组织与检查范围不一致");
      if (!requests.add(request)) throw new IllegalArgumentException("同一计价请求不能重复检查");
    }
    var found = prices.resolveCandidates(context, requests);
    var all = new LinkedHashSet<>(requests);
    // Formula dependencies are actual price inputs; their failures must remain visible too.
    all.addAll(found.values().keySet());
    all.addAll(found.problems().keySet());
    var lines = new ArrayList<Line>();
    for (var request : all) {
      var candidate = found.values().get(request);
      var problem = found.problems().get(request);
      if (candidate != null) {
        var selection = candidate.selection();
        lines.add(new Line(request, !requests.contains(request),
            selection.monthlyPriceId() == null ? "READY" : "ADOPTED", selection, null,
            selection.monthlyPriceId() == null ? "来源可用，正式核算首次使用时采用" : "复用已采用月价及其保存依据"));
      } else if (problem != null) {
        lines.add(new Line(request, !requests.contains(request), problem.reason().name(), null,
            problem.reason().name(), problem.message()));
      } else {
        throw new IllegalStateException("取价检查未返回请求物料的结果");
      }
    }
    return new Preview(context.businessUnitType(), context.month().toString(),
        found.problems().isEmpty(), List.copyOf(lines), List.copyOf(context.supplierReviews().reviews()));
  }
}
