package com.sanhua.marketingcost.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CostRunObjectResult {

  private CostRunContext context;
  private Long sourceCostVersionId;
  private CostRunResultDto result;
  private List<CostRunPartItemDto> partItems = List.of();
  private List<CostRunCostItemDto> costItems = List.of();

  public static CostRunObjectResult of(
      CostRunContext context,
      Long sourceCostVersionId,
      CostRunResultDto result,
      List<CostRunPartItemDto> partItems,
      List<CostRunCostItemDto> costItems) {
    CostRunObjectResult objectResult = new CostRunObjectResult();
    objectResult.setContext(context);
    objectResult.setSourceCostVersionId(sourceCostVersionId);
    objectResult.setResult(result);
    objectResult.setPartItems(partItems == null ? List.of() : List.copyOf(partItems));
    objectResult.setCostItems(costItems == null ? List.of() : List.copyOf(costItems));
    return objectResult;
  }
}
