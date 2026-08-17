package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

/** 技术协作者当前任务范围内的 U9 相似 BOM 候选。 */
public record TechnicalBomCandidateSearchResponse(
    String priceOrgCode,
    String materialOrganizationCode,
    String productSpec,
    String productModel,
    int total,
    List<Candidate> candidates) {

  public TechnicalBomCandidateSearchResponse {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  public record Candidate(
      String productCode,
      String productName,
      String productSpec,
      String productModel,
      String bomPurpose,
      String bomVersion,
      int bomNodeCount,
      int matchScore,
      String matchReason) {}
}
