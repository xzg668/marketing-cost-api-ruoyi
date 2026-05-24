package com.sanhua.marketingcost.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MakePartPriceGenerateRequest {
  private String oaNo;
  private String period;
  private String calcBatchId;
  private String buildBatchId;
  private List<String> parentMaterialNos;
  private Boolean overwriteBatch = true;
}
