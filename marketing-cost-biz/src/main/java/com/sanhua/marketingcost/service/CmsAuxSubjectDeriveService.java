package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsAuxSubjectDeriveResponse;

public interface CmsAuxSubjectDeriveService {
  CmsAuxSubjectDeriveResponse deriveAuxSubjects(Long importBatchId);
}
