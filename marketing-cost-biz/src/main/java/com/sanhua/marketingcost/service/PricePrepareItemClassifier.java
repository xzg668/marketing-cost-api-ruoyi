package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import java.util.List;

public interface PricePrepareItemClassifier {

  List<PricePreparePlanItem> classify(List<BomCostingRow> rows);
}
