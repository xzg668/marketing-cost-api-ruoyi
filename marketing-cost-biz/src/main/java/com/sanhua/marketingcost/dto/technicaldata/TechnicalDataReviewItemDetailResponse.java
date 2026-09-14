package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

public record TechnicalDataReviewItemDetailResponse(
    TechnicalDataReviewTaskResponse.Item reviewItem,
    TechnicalDataProductResponse product,
    Object moduleData,
    List<String> editableModules) {

  public TechnicalDataReviewItemDetailResponse {
    editableModules = editableModules == null ? List.of() : List.copyOf(editableModules);
  }
}
