package com.sanhua.marketingcost.dto;

import java.util.List;

public class OaFormDetailDto {
  private OaFormDetailKeyDto key;
  private List<OaFormDetailItemDto> items;

  public OaFormDetailKeyDto getKey() {
    return key;
  }

  public void setKey(OaFormDetailKeyDto key) {
    this.key = key;
  }

  public List<OaFormDetailItemDto> getItems() {
    return items;
  }

  public void setItems(List<OaFormDetailItemDto> items) {
    this.items = items;
  }
}
