package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.*;
import java.util.*;
import org.springframework.beans.BeanUtils;

/** One consumer's resolved parts and origins; numerical calculation can be supplied separately. */
public final class ResolvedCostRun {
  private final CostRunContext context;
  private final List<CostRunPartItemDto> parts;
  private final ResolvedOrdinaryCost costs;

  public ResolvedCostRun(CostRunContext context, List<CostRunPartItemDto> parts, ResolvedOrdinaryCost costs) {
    this.context = copy(context); this.parts = copyParts(parts); this.costs = Objects.requireNonNull(costs);
  }
  public ResolvedOrdinaryCost.Inputs inputs() { return costs.inputs(); }
  public CostRunContext context() { return copy(context); }
  public List<CostRunPartItemDto> parts() { return copyParts(parts); }

  public CostRunObjectResult bind(ResolvedOrdinaryCost.Calculated numbers) {
    var rows = costs.bind(numbers);
    var head = new CostRunResultDto();
    head.setOaNo(context.getOaNo()); head.setProductCode(context.getProductCode());
    head.setCustomerName(context.getCustomerName()); head.setPeriod(context.getPricingMonth());
    head.setTotalCost(rows.stream().filter(row -> "TOTAL".equals(row.getCostCode()))
        .findFirst().orElseThrow().getAmount());
    head.setCalcStatus(CostRunContext.SCENE_QUOTE.equals(context.getScene()) ? "已核算" : "SUCCESS");
    return CostRunObjectResult.of(context(), context.getCostRunVersionId(), head, parts(), rows);
  }

  private static CostRunContext copy(CostRunContext input) {
    var result = new CostRunContext(); BeanUtils.copyProperties(input, result);
    result.setPriceVariableOverrides(input.getPriceVariableOverrides() == null ? Map.of() : Map.copyOf(input.getPriceVariableOverrides()));
    return result;
  }
  private static List<CostRunPartItemDto> copyParts(List<CostRunPartItemDto> input) {
    return input.stream().map(row -> {
      var copy = new CostRunPartItemDto(); BeanUtils.copyProperties(row, copy); return copy;
    }).toList();
  }
}
