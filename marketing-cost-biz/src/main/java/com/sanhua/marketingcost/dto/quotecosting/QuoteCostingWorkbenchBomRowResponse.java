package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuoteCostingWorkbenchBomRowResponse {
  private Long id;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String parentCode;
  private String childCode;
  private String childName;
  private String childModel;
  private BigDecimal usageQty;
  private BigDecimal qtyPerTop;
  private String unit;
  private String materialAttribute;
  private String shapeAttribute;
  private Integer level;
  private String path;
  private String settlementRowType;
  private Integer subtreeCostRequired;
  private Integer manualModified;
  private String modifiedBy;
  private LocalDateTime modifiedAt;
}
