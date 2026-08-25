package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class QuoteCostingWorkbenchRollupComponentResponse {
  private String childCode;
  private String childName;
  private String childSpec;
  private String childModel;
  private String childUnit;
  private String childMaterialAttribute;
  private String childShapeAttribute;
  private String parentSpec;
  private String parentModel;
  private String parentUnit;
  private String parentMaterialAttribute;
  private String parentShapeAttribute;
  private BigDecimal usageQty;
  private BigDecimal qtyPerTop;
}
