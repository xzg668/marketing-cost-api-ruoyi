package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.PriceLinkedType2RowMatchStatus;
import java.util.List;

/** 一个精确匹配键的完整匹配结果，保留两侧全部候选用于解释阻断原因。 */
public final class PriceLinkedType2RowMatchResult {

  private final PriceLinkedType2MatchKey matchKey;
  private final PriceLinkedType2RowMatchStatus status;
  private final List<PriceLinkedType2ProductRow> businessRows;
  private final List<PriceLinkedType2StandardRow> standardRows;
  private final String message;

  public PriceLinkedType2RowMatchResult(
      PriceLinkedType2MatchKey matchKey,
      PriceLinkedType2RowMatchStatus status,
      List<PriceLinkedType2ProductRow> businessRows,
      List<PriceLinkedType2StandardRow> standardRows,
      String message) {
    this.matchKey = matchKey;
    this.status = status;
    this.businessRows = List.copyOf(businessRows);
    this.standardRows = List.copyOf(standardRows);
    this.message = message;
  }

  public PriceLinkedType2MatchKey getMatchKey() {
    return matchKey;
  }

  public PriceLinkedType2RowMatchStatus getStatus() {
    return status;
  }

  public List<PriceLinkedType2ProductRow> getBusinessRows() {
    return businessRows;
  }

  public List<PriceLinkedType2StandardRow> getStandardRows() {
    return standardRows;
  }

  public String getMessage() {
    return message;
  }

  public boolean isMatched() {
    return status == PriceLinkedType2RowMatchStatus.MATCHED
        || status == PriceLinkedType2RowMatchStatus.MATCHED_SUPPLIER_FALLBACK;
  }

  public PriceLinkedType2ProductRow getMatchedBusinessRow() {
    return isMatched() ? businessRows.getFirst() : null;
  }

  public PriceLinkedType2StandardRow getMatchedStandardRow() {
    return isMatched() ? standardRows.getFirst() : null;
  }
}
