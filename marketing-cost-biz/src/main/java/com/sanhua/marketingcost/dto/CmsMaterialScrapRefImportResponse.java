package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsMaterialScrapRefImportResponse {
  private String status;
  private int sourceRowCount;
  private int effectiveRowCount;
  private int skippedRowCount;
  private int conflictRowCount;
  private int updatedMappingCount;
}
