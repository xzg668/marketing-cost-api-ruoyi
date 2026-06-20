package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuotePriceTypeConfirmationRow {
  private String rowKey;
  private Integer level;
  private String objectType;
  private String materialCode;
  private String materialName;
  private String parentMaterialCode;
  private Long sourceBomRowId;
  private String sourceText;
  private BigDecimal quantity;
  private String priceType;
  private String priceTypeSource;
  private String typeStatus;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private BigDecimal referenceUnitPrice;
  private String message;
  private List<QuotePriceTypeConfirmationRow> children = new ArrayList<>();
}
