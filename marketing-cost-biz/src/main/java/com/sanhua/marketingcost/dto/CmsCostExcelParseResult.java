package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

@Getter
public class CmsCostExcelParseResult<T> {
  private final List<T> rows = new ArrayList<>();
  private final List<CmsCostExcelParseError> errors = new ArrayList<>();

  public boolean hasErrors() {
    return !errors.isEmpty();
  }
}
