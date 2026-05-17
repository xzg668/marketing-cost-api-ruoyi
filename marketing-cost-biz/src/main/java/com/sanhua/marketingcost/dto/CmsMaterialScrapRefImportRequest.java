package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsMaterialScrapRefImportRequest {
  private String businessUnitType;
  private List<CmsMaterialScrapRefSourceRow> sourceRows = new ArrayList<>();
}
