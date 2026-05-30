package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationContextResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSaveResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskDetailResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskQueryRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskQueryResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewResponse;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateResponse;

public interface QuoteBomSupplementCollaborationService {

  QuoteProductBomTaskCreateResponse createTasks(QuoteProductBomTaskCreateRequest request);

  BomSupplementTaskQueryResponse listTasks(BomSupplementTaskQueryRequest request);

  BomSupplementTaskDetailResponse getTaskDetail(Long taskId);

  BomSupplementTaskReviewResponse review(Long taskId, BomSupplementTaskReviewRequest request);

  BomSupplementTaskReviewResponse returnForRevision(Long taskId, BomSupplementTaskReviewRequest request);

  BomSupplementCollaborationContextResponse getContext(String token);

  PackageComponentStructureReadResult readPackageReference(
      String token, Long taskId, String referenceFinishedCode, String sourceTopProductCode, String periodMonth);

  BomSupplementCollaborationSaveResponse saveDraft(
      String token, Long taskId, BomSupplementCollaborationSubmitRequest request);

  BomSupplementCollaborationSaveResponse submit(
      String token, Long taskId, BomSupplementCollaborationSubmitRequest request);
}
