package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import java.util.List;

public class CmsCostBatchPageResponse {
  private long total;
  private List<CmsCostImportBatch> list;

  public CmsCostBatchPageResponse(long total, List<CmsCostImportBatch> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<CmsCostImportBatch> getList() {
    return list;
  }

  public void setList(List<CmsCostImportBatch> list) {
    this.list = list;
  }
}
