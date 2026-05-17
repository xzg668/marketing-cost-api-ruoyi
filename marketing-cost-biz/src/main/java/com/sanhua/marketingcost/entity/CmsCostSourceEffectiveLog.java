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
@TableName("cms_cost_source_effective_log")
public class CmsCostSourceEffectiveLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long effectiveSourceId;
  private Integer costYear;
  private String sourceType;
  private String parentCode;
  private String oldPeriod;
  private String newPeriod;
  private String subjectCode;
  private String subjectName;
  private BigDecimal oldAmountYuan;
  private BigDecimal newAmountYuan;
  private String actionType;
  private String message;
  private String operator;
  private String businessUnitType;
  private LocalDateTime createdAt;
}
