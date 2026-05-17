package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsSalaryCostDeriveResponse;

public interface CmsSalaryCostDeriveService {
  CmsSalaryCostDeriveResponse deriveSalaryCosts(Long importBatchId);
}
