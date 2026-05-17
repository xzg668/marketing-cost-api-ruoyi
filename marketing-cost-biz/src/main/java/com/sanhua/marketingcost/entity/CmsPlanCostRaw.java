package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("cms_plan_cost_raw")
public class CmsPlanCostRaw {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long importBatchId;
  private Integer rowNo;
  private String firstUnitCode;
  private String firstUnitName;
  private String parentCode;
  private String parentName;
  private String parentSpec;
  private String parentType;
  private String unit;
  private BigDecimal workingHours;
  private LocalDate effectiveDate;
  private String effectivePeriod;
  private BigDecimal mainMaterialCost;
  private BigDecimal auxMaterialCost;
  private BigDecimal salaryCost;
  private BigDecimal fundCost;
  private BigDecimal lossCost;
  private BigDecimal totalPlanCost;
  private String businessStatus;
  private String unapprovedItems;
  private String description;
  private String oaNo;
  private String businessUnitType;
  private LocalDateTime createdAt;
}
