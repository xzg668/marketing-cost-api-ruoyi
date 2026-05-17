package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsAuxSubjectDeriveResponse {
  private Long importBatchId;
  private int auxInsertCount;
  private int auxSkipCount;
  private int errorCount;
  private String errorMessage;
}
