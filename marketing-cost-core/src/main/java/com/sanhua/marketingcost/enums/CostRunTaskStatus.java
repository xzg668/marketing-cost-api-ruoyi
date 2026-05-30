package com.sanhua.marketingcost.enums;

import org.springframework.util.StringUtils;

/** 通用成本核算任务状态。 */
public enum CostRunTaskStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  FAILED,
  RETRYABLE,
  CANCELED;

  public static CostRunTaskStatus fromCode(String code) {
    if (!StringUtils.hasText(code)) {
      throw new IllegalArgumentException("成本核算任务状态不能为空");
    }
    for (CostRunTaskStatus status : values()) {
      if (status.name().equalsIgnoreCase(code.trim())) {
        return status;
      }
    }
    throw new IllegalArgumentException("不支持的成本核算任务状态：" + code);
  }
}
