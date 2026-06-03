package com.sanhua.marketingcost.dto.packagecomponent;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageComponentBulkGenerateResult {
  private String oaNo;
  private String topProductCode;
  private String packageMaterialCode;
  private String packageMaterialName;
  private String periodMonth;
  private String status;
  private boolean complete;
  private BigDecimal totalPrice;
  private String message;
}
