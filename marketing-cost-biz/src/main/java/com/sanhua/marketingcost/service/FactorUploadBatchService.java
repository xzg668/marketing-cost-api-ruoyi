package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowRefSaveResult;
import com.sanhua.marketingcost.dto.FactorUploadBatchCreateRequest;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.entity.FactorUploadBatch;

public interface FactorUploadBatchService {

  FactorUploadBatch createFactorBatch(FactorUploadBatchCreateRequest request);

  FactorRowRefSaveResult saveRowRefs(
      Long factorUploadBatchId,
      FactorWorkbookParseResult parseResult,
      FactorMonthlyPriceUpsertResult upsertResult);

  FactorRowRef findRowRef(Long factorUploadBatchId, String sourceSheetName, Integer sourceRowNumber);
}
