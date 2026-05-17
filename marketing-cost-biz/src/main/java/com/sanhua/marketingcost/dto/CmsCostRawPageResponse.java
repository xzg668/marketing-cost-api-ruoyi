package com.sanhua.marketingcost.dto;

import java.util.List;

public class CmsCostRawPageResponse<T> {
  private String rawType;
  private long total;
  private List<T> list;

  public CmsCostRawPageResponse(String rawType, long total, List<T> list) {
    this.rawType = rawType;
    this.total = total;
    this.list = list;
  }

  public String getRawType() {
    return rawType;
  }

  public void setRawType(String rawType) {
    this.rawType = rawType;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<T> getList() {
    return list;
  }

  public void setList(List<T> list) {
    this.list = list;
  }
}
