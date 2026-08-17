package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

/** 裸品补包装的可引用正式来源；只返回当前任务服务端范围内的数据。 */
public record TechnicalPackageSearchResponse(
    String sourceMode,
    int total,
    List<Candidate> candidates) {

  public TechnicalPackageSearchResponse {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  public record Candidate(
      Long sourceId,
      String sourceMode,
      String primaryCode,
      String primaryName,
      String sourceTopProductCode,
      String periodMonth,
      int lineCount,
      boolean approved,
      String description) {}
}
