package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.MaterialPriceType;

/** 正式价格写入时同步物料当前价格类型；类型未变化时不产生重复历史。 */
public interface MaterialPriceTypeRouteSyncService {

  SyncResult sync(RouteCommand command);

  record RouteCommand(
      String materialCode,
      String materialName,
      String materialSpec,
      String unit,
      String businessUnitType,
      String priceType,
      String source,
      String sourceSystem) {}

  record SyncResult(MaterialPriceType route, boolean created) {}
}
