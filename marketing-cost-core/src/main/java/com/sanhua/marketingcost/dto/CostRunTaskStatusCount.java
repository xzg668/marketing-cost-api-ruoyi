package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

/** 通用成本核算任务状态计数。 */
@Getter
@Setter
public class CostRunTaskStatusCount {

  private String status;
  private Long count;
}
