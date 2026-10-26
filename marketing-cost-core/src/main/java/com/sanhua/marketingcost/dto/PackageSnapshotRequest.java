package com.sanhua.marketingcost.dto;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageSnapshotRequest {
  @com.fasterxml.jackson.annotation.JsonIgnore
  private String sourceParentPath;
  @com.fasterxml.jackson.annotation.JsonIgnore
  private Long sourceParentHierarchyId;
  private String packageMaterialCode;
  private String periodMonth;
  private String quoteNo;
  private String oaNo;
  private String topProductCode;
  private String priceOrgCode;
  private String bomPurpose;
  private String sourceType;
  private LocalDate asOfDate;
}
