package com.sanhua.marketingcost.dto.collaboration;

import java.time.LocalDateTime;
import java.util.List;

public record FinanceReviewListResponse(int total, List<Item> items) {
  public record Item(Long reviewId, String reviewNo, String oaNo, int reviewRound,
      String status, int productCount, int priceDraftCount, int passedCount,
      int rejectedCount, LocalDateTime submittedAt) {}
}
