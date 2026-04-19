package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MaterialMaster;
import java.util.List;

public class MaterialMasterPageResponse {
  private long total;
  private List<MaterialMaster> list;

  public MaterialMasterPageResponse(long total, List<MaterialMaster> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<MaterialMaster> getList() {
    return list;
  }

  public void setList(List<MaterialMaster> list) {
    this.list = list;
  }
}
