package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsSyncPublishRunResponse;

public interface CmsSyncPublishService {
  CmsSyncPublishRunResponse runNextReady();
}
