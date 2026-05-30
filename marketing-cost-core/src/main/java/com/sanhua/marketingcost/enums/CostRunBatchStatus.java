package com.sanhua.marketingcost.enums;

import org.springframework.util.StringUtils;

/** 通用成本核算批次状态。 */
public enum CostRunBatchStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  PARTIAL_FAILED,
  FAILED,
  CANCELED;

  public static CostRunBatchStatus fromCode(String code) {
    if (!StringUtils.hasText(code)) {
      throw new IllegalArgumentException("成本核算批次状态不能为空");
    }
    for (CostRunBatchStatus status : values()) {
      if (status.name().equalsIgnoreCase(code.trim())) {
        return status;
      }
    }
    throw new IllegalArgumentException("不支持的成本核算批次状态：" + code);
  }
}
