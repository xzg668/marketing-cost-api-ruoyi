package com.sanhua.marketingcost.service.technicaldata;

import java.time.LocalDateTime;
import java.util.Objects;

/** 只能由后端来源检查构造，不能作为发布接口的请求字段。 */
public record TechnicalDataSourceFact(
    TechnicalDataModuleType moduleType,
    TechnicalDataAvailability availability,
    String reasonCode,
    String reason,
    String sourceReference,
    LocalDateTime checkedAt) {
  public TechnicalDataSourceFact {
    Objects.requireNonNull(moduleType, "moduleType");
    Objects.requireNonNull(availability, "availability");
    if (reasonCode == null || reasonCode.isBlank() || reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("来源检查必须说明原因");
    }
    if (availability != TechnicalDataAvailability.UNCONFIRMED && checkedAt == null) {
      throw new IllegalArgumentException("已执行的来源检查必须保留检查时间");
    }
  }
}
