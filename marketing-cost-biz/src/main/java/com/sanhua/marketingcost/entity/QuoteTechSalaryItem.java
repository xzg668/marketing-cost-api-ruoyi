package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_tech_salary_item")
public class QuoteTechSalaryItem {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long versionId;
  private Integer lineNo;
  private Integer sortSeq;
  private String processCode;
  private String processName;
  private String laborType;
  private BigDecimal workingHours;
  private String originalTimeUnit;
  private BigDecimal standardHours;
  private String standardTimeUnit;
  private BigDecimal conversionFactor;
  private BigDecimal wageRate;
  private String rateUnit;
  private BigDecimal hourlyRate;
  private BigDecimal personCoefficient;
  private BigDecimal amount;
  private String sourceReferenceId;
  private String sourceReferenceVersion;
  private String sourceSnapshotJson;
  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
