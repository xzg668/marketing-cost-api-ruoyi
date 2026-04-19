package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MaterialPriceType;
import java.util.List;

public class MaterialPriceTypePageResponse {
  private long total;
  private List<MaterialPriceType> list;

  public MaterialPriceTypePageResponse(long total, List<MaterialPriceType> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<MaterialPriceType> getList() {
    return list;
  }

  public void setList(List<MaterialPriceType> list) {
    this.list = list;
  }
}
