package com.sanhua.marketingcost.dto.priceprepare;

import com.sanhua.marketingcost.entity.BomCostingRow;
import lombok.Getter;
import lombok.Setter;

/** 价格准备待处理计划行，PPR-03 只做读取和分类，不做取价。 */
@Getter
@Setter
public class PricePreparePlanItem {
  private BomCostingRow bomRow;
  private String topProductCode;
  private Long bomRowId;
  private String materialCode;
  private String materialName;
  private String itemType;
  private String status;
  private String message;
}
