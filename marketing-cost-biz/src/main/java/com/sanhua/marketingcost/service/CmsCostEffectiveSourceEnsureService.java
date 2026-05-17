package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;

public interface CmsCostEffectiveSourceEnsureService {
  CmsEffectiveSourceGenerateResponse ensureDefaultSources(
      int costYear, String operator, String businessUnitType);
}
