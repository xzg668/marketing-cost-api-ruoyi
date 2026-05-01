package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MakePartSpec;
import java.util.List;

/** 自制件工艺规格 分页响应 (V48) */
public class MakePartSpecPageResponse {
  private long total;
  private List<MakePartSpec> list;

  public MakePartSpecPageResponse(long total, List<MakePartSpec> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() { return total; }
  public void setTotal(long total) { this.total = total; }

  public List<MakePartSpec> getList() { return list; }
  public void setList(List<MakePartSpec> list) { this.list = list; }
}
