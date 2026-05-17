package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsMaterialScrapRefPageResponse<T> {
  private long total;
  private List<T> list = new ArrayList<>();

  public CmsMaterialScrapRefPageResponse() {}

  public CmsMaterialScrapRefPageResponse(long total, List<T> list) {
    this.total = total;
    this.list = list == null ? new ArrayList<>() : list;
  }
}
