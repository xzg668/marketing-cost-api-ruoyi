package com.sanhua.marketingcost.dto;

import java.util.List;

/** 类型 2 两个 Sheet 的匹配汇总。 */
public final class PriceLinkedType2RowMatchSummary {

  private final List<PriceLinkedType2RowMatchResult> results;

  public PriceLinkedType2RowMatchSummary(List<PriceLinkedType2RowMatchResult> results) {
    this.results = List.copyOf(results);
  }

  public List<PriceLinkedType2RowMatchResult> getResults() {
    return results;
  }

  public List<PriceLinkedType2RowMatchResult> getMatchedResults() {
    return results.stream().filter(PriceLinkedType2RowMatchResult::isMatched).toList();
  }

  public List<PriceLinkedType2RowMatchResult> getBlockedResults() {
    return results.stream().filter(result -> !result.isMatched()).toList();
  }

  public int getMatchedCount() {
    return (int) results.stream().filter(PriceLinkedType2RowMatchResult::isMatched).count();
  }

  public int getBlockedCount() {
    return results.size() - getMatchedCount();
  }
}
