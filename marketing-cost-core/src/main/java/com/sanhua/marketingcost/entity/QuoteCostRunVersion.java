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
@TableName("lp_quote_cost_run_version")
public class QuoteCostRunVersion {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String costRunNo;
  private String versionNo;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String pricingMonth;
  private String resultPeriod;
  private String bomConfirmNo;
  private String priceTypeConfirmNo;
  private String pricePrepareNo;
  private String status;
  private BigDecimal totalCost;
  private Integer partItemCount;
  private Integer costItemCount;
  private LocalDateTime trialStartedAt;
  private LocalDateTime trialFinishedAt;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String confirmMessage;
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
