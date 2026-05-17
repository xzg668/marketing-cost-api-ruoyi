package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskRequest;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskResponse;

public interface BomSupplementTaskService {
  BomSupplementBatchOaTaskResponse batchCreateAndMockPush(
      BomSupplementBatchOaTaskRequest request);
}
