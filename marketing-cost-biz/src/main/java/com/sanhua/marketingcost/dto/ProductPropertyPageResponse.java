package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.ProductProperty;
import java.util.List;

public class ProductPropertyPageResponse {
  private long total;
  private List<ProductProperty> list;

  public ProductPropertyPageResponse(long total, List<ProductProperty> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<ProductProperty> getList() {
    return list;
  }

  public void setList(List<ProductProperty> list) {
    this.list = list;
  }
}
