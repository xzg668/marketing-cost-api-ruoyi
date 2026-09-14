package com.sanhua.marketingcost.service.technicaldata;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TechnicalDataPackageReferenceRow {
  private Long sourceProductId;
  private Long sourceVersionId;
  private Integer sourceVersionNo;
  private String materialNo;
  private String productName;
  private String productModel;
  private String validFromMonth;
  private String contentFingerprint;
  private Integer itemCount;
}
