package com.sanhua.marketingcost.service.collaboration;

import java.util.List;
import java.util.Optional;

public interface FormalPriceReferenceGateway {

  List<FormalPriceReference> search(
      String businessUnitType,
      String orgCode,
      String accountingMonth,
      String keyword,
      String priceType);

  Optional<FormalPriceReference> findEffective(
      String businessUnitType,
      String orgCode,
      String accountingMonth,
      String sourceType,
      Long sourceId);
}
