package com.sanhua.marketingcost.dto.packagecomponent;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageComponentPriceQueryRequest {
  private String periodMonth;
  private String packageMaterialCode;
  private String topProductCode;
  private String priceStatus;
  private Integer page;
  private Integer pageSize;
}
