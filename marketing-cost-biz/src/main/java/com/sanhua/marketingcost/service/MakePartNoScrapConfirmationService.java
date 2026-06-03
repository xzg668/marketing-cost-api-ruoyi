package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapRevokeRequest;

public interface MakePartNoScrapConfirmationService {

  NoScrapConfirmResponse confirm(NoScrapConfirmRequest request, String operator);

  NoScrapConfirmResponse revoke(Long id, NoScrapRevokeRequest request, String operator);

  NoScrapConfirmationPageResponse page(NoScrapConfirmationPageRequest request);

  NoScrapConfirmResponse findEffective(
      String materialNo, String periodMonth, String businessUnitType);
}
