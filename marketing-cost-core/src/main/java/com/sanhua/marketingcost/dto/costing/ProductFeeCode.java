package com.sanhua.marketingcost.dto.costing;

/** Stable identities for the four optional product unit fees. */
public enum ProductFeeCode {
  MOULD("OTHER_EXP_TECH_MOULD", "unitMouldFee", "模具费"),
  CERTIFICATION("OTHER_EXP_TECH_CERTIFICATION", "unitCertificationFee", "认证费"),
  DEPRECIATION("OTHER_EXP_TECH_DEPRECIATION", "unitDepreciationFee", "折旧费"),
  TOOLING("OTHER_EXP_TECH_TOOLING", "unitToolingFee", "工装夹具费");

  private final String costCode;
  private final String fieldName;
  private final String displayName;

  ProductFeeCode(String costCode, String fieldName, String displayName) {
    this.costCode = costCode;
    this.fieldName = fieldName;
    this.displayName = displayName;
  }

  public String costCode() {
    return costCode;
  }

  public String fieldName() {
    return fieldName;
  }

  public String displayName() {
    return displayName;
  }
}
