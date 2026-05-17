package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustBatchDetailDto {
  private FactorAdjustBatchDto batch;
  private List<FactorAdjustPriceDto> prices = new ArrayList<>();
}
