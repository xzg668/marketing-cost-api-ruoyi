package com.sanhua.marketingcost.dto.quotecosting;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import lombok.Getter;
import lombok.Setter;

/** 工作台上一种价格口径的批次和明细快照。 */
@Getter
@Setter
public class QuotePricePrepareScenarioResponse {
  private String scenarioType;
  private PricePrepareBatch batch;
  private PricePrepareItemPageResponse items;
}
