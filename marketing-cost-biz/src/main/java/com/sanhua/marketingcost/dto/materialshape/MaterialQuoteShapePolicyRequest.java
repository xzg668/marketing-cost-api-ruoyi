package com.sanhua.marketingcost.dto.materialshape;

import lombok.Data;

/** 新增或修改料品报价形态规则的请求。 */
@Data
public class MaterialQuoteShapePolicyRequest {

  private String materialOrgCode;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String policyMode;
  private String fixedTargetShape;
  private String conditionConfigJson;
  private String actionConfigJson;
  private String effectiveFromMonth;
  private String effectiveToMonth;
  private Integer enabled;
  private String remark;
}
