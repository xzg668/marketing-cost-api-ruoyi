package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import java.util.List;

public class FactorQuoteBaseMappingPageResponse {

  private long total;
  private List<FactorQuoteBaseMapping> records;

  public FactorQuoteBaseMappingPageResponse() {
  }

  public FactorQuoteBaseMappingPageResponse(long total, List<FactorQuoteBaseMapping> records) {
    this.total = total;
    this.records = records;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<FactorQuoteBaseMapping> getRecords() {
    return records;
  }

  public void setRecords(List<FactorQuoteBaseMapping> records) {
    this.records = records;
  }
}
