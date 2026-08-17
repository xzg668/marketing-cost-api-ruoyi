package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

public record QuoteTechnicianCandidatesResponse(
    Long itemId,
    String matchStatus,
    String message,
    List<Candidate> candidates) {

  public QuoteTechnicianCandidatesResponse {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  public record Candidate(
      Long userId,
      String userName,
      String loginName,
      String oaUserId,
      String jobNo,
      boolean recommended,
      String ruleCode,
      String ruleName) {}
}
