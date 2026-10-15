package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;

public interface CmsSalaryCostSourceEffectiveService {
  CmsEffectiveSourceGenerateResponse generateDefaultSources(
      int costYear, String operator, String businessUnitType);

  /** 同步默认来源，只记录业务值变化并撤销本次已失效的默认来源。 */
  CmsEffectiveSourceGenerateResponse synchronizeDefaultSources(
      int costYear, String operator, String businessUnitType);

  CmsEffectiveSourceGenerateResponse ensureDefaultSources(
      int costYear, String operator, String businessUnitType);

  CmsCostSourceEffective refreshSource(
      CmsEffectiveSourceRefreshRequest request, String operator, String businessUnitType);

  CmsEffectiveSourceGenerateResponse refreshParentPeriod(
      CmsEffectiveSourceRefreshRequest request, String operator, String businessUnitType);
}
