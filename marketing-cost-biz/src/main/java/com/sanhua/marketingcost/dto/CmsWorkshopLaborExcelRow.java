package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsWorkshopLaborExcelRow {
  private Integer rowNo;
  private String period;
  private String firstUnitCode;
  private String firstUnitName;
  private String parentCode;
  private String parentName;
  private String parentSpec;
  private String parentType;
  private String lastUnitName;
  private String lastUnitCode;
  private BigDecimal workingHours;
  private BigDecimal funding;
  private BigDecimal workingCostCent;
  private BigDecimal workingCostYuan;
  private String buildFlag;
  private String path;
  private String sourceRowId;
  private String sequenceNo;
  private String sequenceStatus;
  private BigDecimal materialPrice;
  private BigDecimal materialPriceYuan;
  private String firstSubjectCode;
  private String firstSubjectName;
  private String secondSubjectCode;
  private String secondSubjectName;
  private String thirdSubjectCode;
  private String thirdSubjectName;
}
