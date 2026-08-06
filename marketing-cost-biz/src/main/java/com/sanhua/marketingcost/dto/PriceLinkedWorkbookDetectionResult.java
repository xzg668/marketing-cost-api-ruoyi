package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.PriceLinkedWorkbookType;
import java.util.List;

/** 联动价工作簿识别结果，只描述候选结构，不解析或写入业务数据。 */
public class PriceLinkedWorkbookDetectionResult {

  private final PriceLinkedWorkbookType type;
  private final List<String> standardCandidateSheets;
  private final List<String> type2CandidateSheets;
  private final String message;

  public PriceLinkedWorkbookDetectionResult(
      PriceLinkedWorkbookType type,
      List<String> standardCandidateSheets,
      List<String> type2CandidateSheets,
      String message) {
    this.type = type;
    this.standardCandidateSheets = List.copyOf(standardCandidateSheets);
    this.type2CandidateSheets = List.copyOf(type2CandidateSheets);
    this.message = message;
  }

  public PriceLinkedWorkbookType getType() {
    return type;
  }

  public List<String> getStandardCandidateSheets() {
    return standardCandidateSheets;
  }

  public List<String> getType2CandidateSheets() {
    return type2CandidateSheets;
  }

  public String getMessage() {
    return message;
  }
}
