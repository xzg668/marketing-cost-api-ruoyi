package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MakePartProcessTypeResult {
  private String itemProcessType;
  private String status;
  private String remark;

  public static MakePartProcessTypeResult of(String itemProcessType, String status, String remark) {
    MakePartProcessTypeResult result = new MakePartProcessTypeResult();
    result.setItemProcessType(itemProcessType);
    result.setStatus(status);
    result.setRemark(remark);
    return result;
  }
}
