package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.AuxSubject;
import java.util.List;

public class AuxSubjectPageResponse {
  private long total;
  private List<AuxSubject> list;

  public AuxSubjectPageResponse(long total, List<AuxSubject> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<AuxSubject> getList() {
    return list;
  }

  public void setList(List<AuxSubject> list) {
    this.list = list;
  }
}
