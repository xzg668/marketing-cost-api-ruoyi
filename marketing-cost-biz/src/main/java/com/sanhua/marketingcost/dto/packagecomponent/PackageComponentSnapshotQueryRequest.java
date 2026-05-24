package com.sanhua.marketingcost.dto.packagecomponent;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageComponentSnapshotQueryRequest {
  private String periodMonth;
  private String packageMaterialCode;
  private String status;
  private Integer page;
  private Integer pageSize;
}
