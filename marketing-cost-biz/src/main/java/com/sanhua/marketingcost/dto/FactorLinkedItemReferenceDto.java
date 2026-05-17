package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorLinkedItemReferenceDto {
  private Long bindingId;
  private Long linkedItemId;
  private Long factorIdentityId;
  private String pricingMonth;
  private String businessUnitType;
  private String materialCode;
  private String materialName;
  private String supplierName;
  private String supplierCode;
  private String formulaExpr;
  private String formulaExprCn;
  private String tokenName;
  private String bindingSource;
}
