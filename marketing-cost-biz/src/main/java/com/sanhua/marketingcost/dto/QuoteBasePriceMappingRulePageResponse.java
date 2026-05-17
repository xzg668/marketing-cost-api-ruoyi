package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.QuoteBasePriceMappingRule;
import java.util.List;

public class QuoteBasePriceMappingRulePageResponse {

  private long total;
  private List<QuoteBasePriceMappingRule> records;

  public QuoteBasePriceMappingRulePageResponse() {
  }

  public QuoteBasePriceMappingRulePageResponse(
      long total, List<QuoteBasePriceMappingRule> records) {
    this.total = total;
    this.records = records;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<QuoteBasePriceMappingRule> getRecords() {
    return records;
  }

  public void setRecords(List<QuoteBasePriceMappingRule> records) {
    this.records = records;
  }
}
