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
@TableName("lp_cost_run_trace_snapshot")
public class CostRunTraceSnapshot {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long costRunVersionId;
  private String costRunNo;
  private String versionNo;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String pricingMonth;
  private String traceType;
  private String traceKey;
  private Long partItemId;
  private Long costItemId;
  private Long bomRowId;
  private Long pricePrepareItemId;
  private String materialCode;
  private String materialName;
  private String costCode;
  private String costName;
  private String sourceType;
  private String sourceBatchNo;
  private Long sourceRefId;
  private BigDecimal unitPrice;
  private BigDecimal quantity;
  private BigDecimal baseAmount;
  private BigDecimal rate;
  private BigDecimal amount;
  private String summary;
  private String sourceSnapshotJson;
  private String formulaSnapshotJson;
  private String variablesJson;
  private String stepsJson;
  private String childrenJson;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
