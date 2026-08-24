package com.sanhua.marketingcost.dto.ingest;

import com.sanhua.marketingcost.dto.MonthlyRepriceCostItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepricePartItemDto;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuoteMonthlyCostResultDetailResponse {
  private QuoteCostResultHistoryItemResponse result;
  private List<MonthlyRepricePartItemDto> partItems = new ArrayList<>();
  private List<MonthlyRepriceCostItemDto> costItems = new ArrayList<>();
}
