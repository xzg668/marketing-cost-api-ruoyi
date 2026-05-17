package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;

public interface CmsSalaryCostSourceEffectiveService {
  CmsEffectiveSourceGenerateResponse generateDefaultSources(
      int costYear, String operator, String businessUnitType);

  CmsEffectiveSourceGenerateResponse ensureDefaultSources(
      int costYear, String operator, String businessUnitType);

  CmsCostSourceEffective refreshSource(
      CmsEffectiveSourceRefreshRequest request, String operator, String businessUnitType);

  CmsEffectiveSourceGenerateResponse refreshParentPeriod(
      CmsEffectiveSourceRefreshRequest request, String operator, String businessUnitType);
}
