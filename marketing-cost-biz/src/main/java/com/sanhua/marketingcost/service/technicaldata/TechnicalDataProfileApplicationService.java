package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;

public interface TechnicalDataProfileApplicationService {
  TechnicalDataProfileResponse save(
      Long productId,
      TechnicalDataProfileUpdateRequest request,
      TechnicalDataActor actor);
}
