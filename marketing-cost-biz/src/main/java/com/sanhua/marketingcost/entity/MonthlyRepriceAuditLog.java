package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 月度调价审计日志表。 */
@Getter
@Setter
@TableName("lp_monthly_reprice_audit_log")
public class MonthlyRepriceAuditLog {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String repriceNo;
  private String pricingMonth;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String operationType;
  private String operationName;
  private String operatorId;
  private String operatorName;
  private String operatorRole;
  private LocalDateTime operationTime;
  private String targetType;
  private String targetId;
  private String targetKey;
  private String beforeJson;
  private String afterJson;
  private String changeSummary;
  private String requestIp;
  private String requestUserAgent;
  private String requestId;
  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}

