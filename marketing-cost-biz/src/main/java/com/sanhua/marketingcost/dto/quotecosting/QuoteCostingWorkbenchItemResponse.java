package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class QuoteCostingWorkbenchItemResponse {
  private Long id;
  private Integer seq;
  private String externalLineId;
  private String materialNo;
  private String productName;
  private String sunlModel;
  private String businessType;
  private String packageType;
  private String packageMethod;
  private String packageComponentCode;
  private BigDecimal annualVolume;
  private BigDecimal totalWithShip;
  private BigDecimal totalNoShip;
  private String technicianName;
  private String classificationStatus;
  private String calcStatus;
  private String businessUnitType;
}
