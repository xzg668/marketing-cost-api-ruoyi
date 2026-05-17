package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("cms_workshop_labor_raw")
public class CmsWorkshopLaborRaw {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long importBatchId;
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
  private String firstSubjectCode;
  private String firstSubjectName;
  private String secondSubjectCode;
  private String secondSubjectName;
  private String thirdSubjectCode;
  private String thirdSubjectName;
  private String businessUnitType;
  private LocalDateTime createdAt;
}
