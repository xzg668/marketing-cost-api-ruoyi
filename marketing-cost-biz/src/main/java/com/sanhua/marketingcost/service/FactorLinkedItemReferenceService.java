package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FactorLinkedItemReferenceDto;
import java.util.List;

public interface FactorLinkedItemReferenceService {

  List<FactorLinkedItemReferenceDto> listLinkedItems(
      Long factorIdentityId, String pricingMonth, String businessUnitType);
}
