package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsProductSubjectCostExcelRow {
  private Integer rowNo;
  private String period;
  private String firstUnitCode;
  private String firstUnitName;
  private String parentCode;
  private String parentName;
  private String parentSpec;
  private String parentType;
  private String lastSubjectCode;
  private String lastSubjectName;
  private String lastSubjectLevel;
  private BigDecimal materialPrice;
  private BigDecimal materialPriceYuan;
  private String buildFlag;
  private String path;
  private String firstSubjectCode;
  private String firstSubjectName;
  private String secondSubjectCode;
  private String secondSubjectName;
  private String thirdSubjectCode;
  private String thirdSubjectName;
  private String sourceRowId;
  private String sequenceNo;
  private String sequenceStatus;
}
