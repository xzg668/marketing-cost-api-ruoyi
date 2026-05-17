package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsMaterialScrapRefPageResponse;
import com.sanhua.marketingcost.entity.MaterialScrapRef;

public interface CmsMaterialScrapRefQueryService {
  CmsMaterialScrapRefPageResponse<MaterialScrapRef> pageCurrent(
      String materialCode,
      String scrapCode,
      String keyword,
      int current,
      int size,
      String businessUnitType);
}
