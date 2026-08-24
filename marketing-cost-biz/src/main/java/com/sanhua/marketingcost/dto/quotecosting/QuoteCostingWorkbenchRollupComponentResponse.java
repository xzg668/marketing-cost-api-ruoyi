package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class QuoteCostingWorkbenchRollupComponentResponse {
  private String childCode;
  private String childName;
  private String childDrawingNo;
  private String parentDrawingNo;
  private BigDecimal usageQty;
  private BigDecimal qtyPerTop;
  private String unit;
}
