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
@TableName("cms_cost_derive_log")
public class CmsCostDeriveLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long importBatchId;
  private String deriveType;
  private String parentCode;
  private String subjectCode;
  private String subjectName;
  private String period;
  private String status;
  private String message;
  private BigDecimal amount;
  private String targetTable;
  private Long targetId;
  private String businessUnitType;
  private LocalDateTime createdAt;
}
