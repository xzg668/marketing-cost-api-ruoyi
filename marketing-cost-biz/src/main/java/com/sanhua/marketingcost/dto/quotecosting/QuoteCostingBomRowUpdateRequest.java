package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class QuoteCostingBomRowUpdateRequest {
  private String childCode;
  private String childName;
  private String childModel;
  private BigDecimal usageQty;
  private String unit;
  private String materialAttribute;
  private String shapeAttribute;
}
