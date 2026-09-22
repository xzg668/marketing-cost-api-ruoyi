package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

/** 一次批量请求的逐产品结果；每项都有独立任务和版本。 */
public record TechnicalDataTaskPublishResponse(String requestId, List<Item> items) {
  public TechnicalDataTaskPublishResponse {
    items = List.copyOf(items);
  }

  public record Item(Long oaFormItemId, String action, TechnicalDataTaskResponse task) {}
}
