package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("cms_cost_source_effective")
public class CmsCostSourceEffective {
  public record SalaryModelMatch(String productCode, String model) {}

  @TableId(type = IdType.AUTO)
  private Long id;
  private Integer costYear;
  private String sourceType;
  private String parentCode;
  private String period;
  private String subjectCode;
  private String subjectName;
  private String sourceTable;
  private String sourceRowIds;
  private BigDecimal amountYuan;
  private Integer defaultFlag;
  private String refreshReason;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String businessUnitType;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  @TableField(exist = false)
  private String unapprovedItems;
  @TableField(exist = false)
  private BigDecimal shapeExcludedAmount;
  @TableField(exist = false)
  private java.util.List<java.util.Map<String,String>> shapeExclusionRows;
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @TableField(exist = false)
  private SalaryModelMatch salaryModelMatch;
}
