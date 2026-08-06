package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedType2FactorIdentityResolution;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import java.util.List;

/** 类型 2 影响因素统一身份的只读解析服务。 */
public interface PriceLinkedType2FactorIdentityResolver {

  PriceLinkedType2FactorIdentityResolution resolve(
      PriceLinkedType2FactorRow factorRow,
      String businessUnitType,
      String priceMonth);

  default List<PriceLinkedType2FactorIdentityResolution> resolve(
      List<PriceLinkedType2FactorRow> factorRows,
      String businessUnitType,
      String priceMonth) {
    if (factorRows == null) {
      return List.of();
    }
    return factorRows.stream()
        .map(row -> resolve(row, businessUnitType, priceMonth))
        .toList();
  }
}
