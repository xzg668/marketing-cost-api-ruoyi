package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncResponse;
import java.util.List;

public interface QuoteBomStatusService {
  QuoteBomStatusResponse listByOaNo(String oaNo);

  QuoteBomStatusResponse checkByOaNo(String oaNo);

  QuoteBomStatusResponse checkForCostRun(String oaNo);

  QuoteBomBatchSyncResponse batchSyncFromU9Source(List<Long> oaFormItemIds);
}
