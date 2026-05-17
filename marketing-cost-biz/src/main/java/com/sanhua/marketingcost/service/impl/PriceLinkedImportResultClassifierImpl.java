package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceItemImportResponse.BindingError;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportResultClassifyRequest;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import com.sanhua.marketingcost.service.PriceLinkedImportResultClassifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedImportResultClassifierImpl implements PriceLinkedImportResultClassifier {

  @Override
  public void append(
      PriceItemImportResponse response,
      PriceLinkedImportResultClassifyRequest request) {
    if (response == null || request == null) {
      return;
    }
    if (!Boolean.TRUE.equals(request.getFormulaAvailable())) {
      addError(response, baseError(request, "单价列无公式，请重新导入带公式的 Excel 或人工配置"));
    }
    appendResolvedRefErrors(response, request);
    appendStandardDecisions(response, request);
    appendWriteResult(response, request);
  }

  private void appendResolvedRefErrors(
      PriceItemImportResponse response,
      PriceLinkedImportResultClassifyRequest request) {
    for (ResolvedFactorRef ref : request.getResolvedRefs()) {
      if (ref == null || ref.isResolved() || !StringUtils.hasText(ref.getErrorMessage())) {
        continue;
      }
      BindingError error = baseError(request, ref.getErrorMessage());
      error.setRefSheet(ref.getSheetName());
      error.setRefRow(ref.getRowNumber());
      error.setNewFactorIdentity(ref.getFactorIdentityId());
      addError(response, error);
    }
  }

  private void appendStandardDecisions(
      PriceItemImportResponse response,
      PriceLinkedImportResultClassifyRequest request) {
    for (StandardBindingDecision decision : request.getStandardDecisions()) {
      if (decision == null) {
        continue;
      }
      switch (decision.getAction()) {
        case PriceLinkedStandardBindingServiceImpl.ACTION_CREATE_HISTORY ->
            response.setNewHistoryBindingCount(response.getNewHistoryBindingCount() + 1);
        case PriceLinkedStandardBindingServiceImpl.ACTION_CONSISTENT ->
            response.setConsistentHistoryBindingCount(
                response.getConsistentHistoryBindingCount() + 1);
        case PriceLinkedStandardBindingServiceImpl.ACTION_CONFLICT -> {
          response.setConflictBindingCount(response.getConflictBindingCount() + 1);
          addError(response, errorFromDecision(request, decision));
        }
        case PriceLinkedStandardBindingServiceImpl.ACTION_FAILED ->
            addError(response, errorFromDecision(request, decision));
        default -> {
          if (StringUtils.hasText(decision.getReason())) {
            addError(response, errorFromDecision(request, decision));
          }
        }
      }
    }
  }

  private void appendWriteResult(
      PriceItemImportResponse response,
      PriceLinkedImportResultClassifyRequest request) {
    PriceLinkedAutoBindingWriteResult writeResult = request.getWriteResult();
    if (writeResult == null) {
      return;
    }
    response.setAutoBindingCount(response.getAutoBindingCount() + writeResult.getWrittenCount());
    response.setManualSkippedCount(
        response.getManualSkippedCount() + writeResult.getManualSkippedCount());
    for (PriceLinkedAutoBindingWriteResult.RowResult row : writeResult.getRows()) {
      if (row == null) {
        continue;
      }
      if ("SKIPPED_MANUAL".equals(row.getAction())) {
        addError(response, baseError(request, row.getReason()));
      } else if ("ERROR".equals(row.getAction())) {
        addError(response, baseError(request, row.getReason()));
      }
    }
  }

  private BindingError errorFromDecision(
      PriceLinkedImportResultClassifyRequest request,
      StandardBindingDecision decision) {
    BindingError error = baseError(request, decision.getReason());
    error.setExistingFactorIdentity(decision.getOldFactorIdentityId());
    error.setNewFactorIdentity(decision.getNewFactorIdentityId());
    BindingCandidate candidate = decision.getCandidate();
    if (candidate != null && candidate.getSourceRef() != null) {
      error.setRefSheet(candidate.getSourceRef().getSheetName());
      error.setRefRow(candidate.getSourceRef().getRowNumber());
    }
    return error;
  }

  private BindingError baseError(
      PriceLinkedImportResultClassifyRequest request, String reason) {
    BindingError error = new BindingError();
    error.setExcelRowNumber(request.getExcelRowNumber());
    error.setMaterialCode(request.getMaterialCode());
    error.setFormula(request.getFormula());
    error.setReason(reason);
    return error;
  }

  private void addError(PriceItemImportResponse response, BindingError error) {
    response.getBindingErrors().add(error);
    response.setBindingErrorCount(response.getBindingErrorCount() + 1);
  }
}
