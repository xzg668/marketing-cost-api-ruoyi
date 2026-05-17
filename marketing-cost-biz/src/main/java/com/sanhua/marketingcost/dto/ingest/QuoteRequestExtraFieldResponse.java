package com.sanhua.marketingcost.dto.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

@Data
public class QuoteRequestExtraFieldResponse {
  private Long id;
  private Long oaFormItemId;
  private String fieldCode;
  private String fieldName;
  private String fieldValue;
  private BigDecimal fieldValueNumber;
  private LocalDate fieldValueDate;
  private String valueType;
  private String sourceFieldName;
  private String sourceFieldPath;
}
