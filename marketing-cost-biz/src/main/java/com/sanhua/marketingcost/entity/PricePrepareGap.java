package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmResponse;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 价格准备缺口表，当前阶段只沉淀待补充事项，不触发 OA 推送。 */
@Getter
@Setter
@TableName("lp_price_prepare_gap")
public class PricePrepareGap {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String prepareNo;
  private String periodMonth;
  private String priceTypeConfirmNo;
  private Long priceTypeConfirmItemId;
  private String actionType;
  private String actionTarget;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String materialCode;
  private String gapMaterialCode;
  private String gapType;
  private String itemType;
  private String sourceTable;
  private String message;
  private String oaPushStatus;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  @TableField(exist = false)
  private String actionMaterialNo;

  @TableField(exist = false)
  private Boolean canConfirmNoScrap;

  @TableField(exist = false)
  private Long noScrapConfirmationId;

  @TableField(exist = false)
  private String noScrapConfirmationStatus;

  @TableField(exist = false)
  private NoScrapConfirmResponse noScrapConfirmation;

  @TableField(exist = false)
  private String confirmedBy;

  @TableField(exist = false)
  private LocalDateTime confirmedAt;

  @TableField(exist = false)
  private String confirmReason;
}
