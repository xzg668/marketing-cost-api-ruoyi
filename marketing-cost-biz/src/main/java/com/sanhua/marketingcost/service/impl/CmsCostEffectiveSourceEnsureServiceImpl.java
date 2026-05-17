package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsCostEffectiveSourceEnsureService;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CmsCostEffectiveSourceEnsureServiceImpl
    implements CmsCostEffectiveSourceEnsureService {

  private final CmsSalaryCostSourceEffectiveService salaryEffectiveService;
  private final CmsAuxSubjectSourceEffectiveService auxEffectiveService;

  public CmsCostEffectiveSourceEnsureServiceImpl(
      CmsSalaryCostSourceEffectiveService salaryEffectiveService,
      CmsAuxSubjectSourceEffectiveService auxEffectiveService) {
    this.salaryEffectiveService = salaryEffectiveService;
    this.auxEffectiveService = auxEffectiveService;
  }

  @Override
  @Transactional
  public CmsEffectiveSourceGenerateResponse ensureDefaultSources(
      int costYear, String operator, String businessUnitType) {
    CmsEffectiveSourceGenerateResponse salary =
        salaryEffectiveService.ensureDefaultSources(costYear, operator, businessUnitType);
    CmsEffectiveSourceGenerateResponse aux =
        auxEffectiveService.ensureDefaultSources(costYear, operator, businessUnitType);
    CmsEffectiveSourceGenerateResponse response = new CmsEffectiveSourceGenerateResponse();
    response.setCostYear(costYear);
    response.setInsertedCount(salary.getInsertedCount() + aux.getInsertedCount());
    response.setUpdatedCount(salary.getUpdatedCount() + aux.getUpdatedCount());
    response.setSkippedCount(salary.getSkippedCount() + aux.getSkippedCount());
    response.setBlockedCount(salary.getBlockedCount() + aux.getBlockedCount());
    response.setErrorCount(salary.getErrorCount() + aux.getErrorCount());
    return response;
  }
}
