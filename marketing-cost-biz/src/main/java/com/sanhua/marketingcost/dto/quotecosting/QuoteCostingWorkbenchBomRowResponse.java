package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuoteCostingWorkbenchBomRowResponse {
  private Long id;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String priceOrgCode;
  private String materialOrganizationCode;
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
  /** 上卷父件按命中子件生成见机表展示名称所需的数据，不代表新增结算行。 */
  private List<QuoteCostingWorkbenchRollupComponentResponse> rollupComponents =
      new ArrayList<>();
}
