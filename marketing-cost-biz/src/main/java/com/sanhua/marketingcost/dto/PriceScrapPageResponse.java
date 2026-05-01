package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PriceScrap;
import java.util.List;

/** 废料回收价 分页响应 (V48) */
public class PriceScrapPageResponse {
  private long total;
  private List<PriceScrap> list;

  public PriceScrapPageResponse(long total, List<PriceScrap> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() { return total; }
  public void setTotal(long total) { this.total = total; }

  public List<PriceScrap> getList() { return list; }
  public void setList(List<PriceScrap> list) { this.list = list; }
}
