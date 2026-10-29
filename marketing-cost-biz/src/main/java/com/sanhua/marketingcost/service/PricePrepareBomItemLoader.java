package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.BomCostingRow;
import java.util.List;

public interface PricePrepareBomItemLoader {

  List<BomCostingRow> loadForCalculation(String oaNo, Long itemId, String productCode,
      String month, String calculationScope);

  List<BomCostingRow> loadByQuoteItem(
      String oaNo, Long oaFormItemId, String topProductCode, String periodMonth);
}
