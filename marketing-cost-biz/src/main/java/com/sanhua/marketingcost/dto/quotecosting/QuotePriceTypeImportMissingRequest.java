package com.sanhua.marketingcost.dto.quotecosting;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuotePriceTypeImportMissingRequest {
  private String periodMonth;
  private List<Item> items = new ArrayList<>();

  @Data
  public static class Item {
    private String materialCode;
    private String materialName;
    private String objectType;
    private String priceType;
    private String effectiveFrom;
    private String reason;
  }
}
