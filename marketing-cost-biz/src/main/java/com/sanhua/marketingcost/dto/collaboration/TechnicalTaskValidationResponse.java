package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

public record TechnicalTaskValidationResponse(
    boolean passed,
    String message,
    List<Issue> issues,
    TechnicalTaskDetailResponse task) {
  public TechnicalTaskValidationResponse {
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public record Issue(String category, String code, String message) {}
}
