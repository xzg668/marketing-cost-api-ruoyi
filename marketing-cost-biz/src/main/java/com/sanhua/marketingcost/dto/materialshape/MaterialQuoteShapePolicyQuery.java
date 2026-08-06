package com.sanhua.marketingcost.dto.materialshape;

import lombok.Data;

/** 料品报价形态规则列表筛选条件。 */
@Data
public class MaterialQuoteShapePolicyQuery {

  private String materialOrgCode;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String policyMode;
  private Integer enabled;
  private String effectiveMonth;
}
