package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.service.MaterialPricePriorityService.Configuration;
import java.time.LocalDateTime;
import java.util.List;

public record MaterialPriceTypePageResponse(long total, List<Row> list) {
  public record Row(Long id, String materialName, Configuration configuration,
      String latestPriceSource, String latestOaNo, LocalDateTime latestResolvedAt,
      boolean currentPriorityApplied, Long priorityUpdatedBy, LocalDateTime priorityUpdatedAt) {}
}
