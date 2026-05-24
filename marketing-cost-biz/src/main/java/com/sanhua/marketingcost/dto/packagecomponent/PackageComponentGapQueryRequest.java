package com.sanhua.marketingcost.dto.packagecomponent;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageComponentGapQueryRequest {
  private String periodMonth;
  private String packageMaterialCode;
  private String gapType;
  private String oaPushStatus;
  private Integer page;
  private Integer pageSize;
}
