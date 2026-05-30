package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_business_change_log")
public class BusinessChangeLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String bizDomain;
  private String bizType;
  private Long bizId;
  private Long bizDetailId;
  private String oaNo;
  private Long oaFormItemId;
  private Long taskId;
  private String fieldName;
  private String fieldLabel;
  private String beforeValue;
  private String afterValue;
  private String changeReason;
  private Long changedBy;
  private String changedByName;
  private LocalDateTime changedAt;
  private String changeSource;
  private String submitBatchNo;
  private String requestId;
  private LocalDateTime createdAt;
}
