package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;
import java.util.List;

public record TechnicalDataTaskValidationResponse(
    Long taskId,
    String taskNo,
    boolean valid,
    int productCount,
    LocalDateTime checkedAt,
    List<ProductValidation> products,
    List<Issue> issues) {

  public TechnicalDataTaskValidationResponse {
    products = products == null ? List.of() : List.copyOf(products);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public record ProductValidation(
      Long productId,
      Long oaFormItemId,
      String materialNo,
      String productName,
      Long draftVersionId,
      Integer draftVersionNo,
      boolean valid,
      int requiredModuleCount,
      int readyModuleCount) {}

  public record Issue(
      Long productId,
      Long oaFormItemId,
      String materialNo,
      String productName,
      String moduleType,
      String field,
      Integer lineNo,
      String code,
      String message,
      String anchor) {}
}
