package com.sanhua.marketingcost.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsSyncPublishRunResponse {
  private boolean executed;
  private String status;
  private String message;
  private Long signalId;
  private String batchNo;
  private Integer costYear;
  private String businessUnitType;
  private Map<String, Long> tmpCounts = new LinkedHashMap<>();
  private Map<String, Integer> publishedCounts = new LinkedHashMap<>();
  private int effectiveInsertedCount;
  private int effectiveUpdatedCount;
  private int effectiveSkippedCount;
  private int effectiveBlockedCount;
  private int effectiveErrorCount;
}
