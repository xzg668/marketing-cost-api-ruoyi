package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.BomCostingRow;
import java.util.List;

public interface PricePrepareBomItemLoader {

  List<BomCostingRow> loadByOaNo(String oaNo);

  List<BomCostingRow> loadByOaNoAndTopProducts(String oaNo, List<String> topProductCodes);
}
