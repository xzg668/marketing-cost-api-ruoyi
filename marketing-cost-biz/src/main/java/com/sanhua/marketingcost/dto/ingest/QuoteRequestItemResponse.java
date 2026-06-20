package com.sanhua.marketingcost.dto.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuoteRequestItemResponse {
  private Long id;
  private Integer seq;
  private String externalLineId;
  private String productName;
  private String materialNo;
  private String sunlModel;
  private String customerCode;
  private String customerDrawing;
  private String spec;
  private String productAttr;
  private String businessType;
  private Integer firstQuoteFlag;
  private Integer certificationRequired;
  private String originCountry;
  private String packageType;
  private String packageMethod;
  private String packageComponentCode;
  private BigDecimal packageQty;
  private BigDecimal shippingFee;
  private BigDecimal supportQty;
  private BigDecimal annualVolume;
  private String projectNo;
  private String productStatus;
  private BigDecimal scrapRate;
  private BigDecimal unitLaborCost;
  private String technicianName;
  private String classificationStatus;
  private BigDecimal totalWithShip;
  private BigDecimal totalNoShip;
  private BigDecimal materialCost;
  private BigDecimal laborCost;
  private BigDecimal manufacturingCost;
  private BigDecimal managementCost;
  private Integer validMonth;
  private BigDecimal sus304WeightG;
  private BigDecimal sus316WeightG;
  private BigDecimal copperWeightG;
  private String businessUnitType;
  private LocalDate validDate;
  private String calcStatus;
  private LocalDateTime calcAt;
  private Long confirmedCostVersionId;
  private QuoteBomStatusItemResponse bomStatus;
}
