package com.sanhua.marketingcost.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CostRunObjectResult {

  private CostRunContext context;
  private Long sourceCostResultId;
  private CostRunResultDto result;
  private List<CostRunPartItemDto> partItems = List.of();
  private List<CostRunCostItemDto> costItems = List.of();

  public static CostRunObjectResult of(
      CostRunContext context,
      Long sourceCostResultId,
      CostRunResultDto result,
      List<CostRunPartItemDto> partItems,
      List<CostRunCostItemDto> costItems) {
    CostRunObjectResult objectResult = new CostRunObjectResult();
    objectResult.setContext(context);
    objectResult.setSourceCostResultId(sourceCostResultId);
    objectResult.setResult(result);
    objectResult.setPartItems(partItems == null ? List.of() : List.copyOf(partItems));
    objectResult.setCostItems(costItems == null ? List.of() : List.copyOf(costItems));
    return objectResult;
  }
}
