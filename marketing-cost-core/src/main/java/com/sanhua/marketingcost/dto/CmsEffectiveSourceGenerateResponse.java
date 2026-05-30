package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsEffectiveSourceGenerateResponse {
  private Integer costYear;
  private int insertedCount;
  private int updatedCount;
  private int skippedCount;
  private int blockedCount;
  private int errorCount;
  private String errorMessage;
}
