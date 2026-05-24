package com.sanhua.marketingcost.dto;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageSnapshotRequest {
  private String packageMaterialCode;
  private String periodMonth;
  private String quoteNo;
  private String oaNo;
  private String topProductCode;
  private String bomPurpose;
  private String sourceType;
  private LocalDate asOfDate;
}
