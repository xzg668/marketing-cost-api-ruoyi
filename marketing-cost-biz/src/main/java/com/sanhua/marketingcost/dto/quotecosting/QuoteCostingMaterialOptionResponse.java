package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

@Data
public class QuoteCostingMaterialOptionResponse {
  private Long id;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String childModel;
  private String unit;
  private String materialAttribute;
  private String shapeAttribute;
}
