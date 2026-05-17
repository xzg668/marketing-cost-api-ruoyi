package com.sanhua.marketingcost.dto.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
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
  private BigDecimal annualVolume;
  private String projectNo;
  private String technicianName;
  private String classificationStatus;
  private String businessUnitType;
  private LocalDate validDate;
  private QuoteBomStatusItemResponse bomStatus;
}
