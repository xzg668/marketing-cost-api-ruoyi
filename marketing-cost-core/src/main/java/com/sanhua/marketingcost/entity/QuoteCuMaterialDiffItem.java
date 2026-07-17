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

/** 财务 Cu 基准与 OA 锁价的逐结算键材料费差异明细。 */
@Getter
@Setter
@TableName("lp_quote_cu_material_diff_item")
public class QuoteCuMaterialDiffItem {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long costRunVersionId;
  private String costRunNo;
  private Integer lineNo;
  private String settlementKey;
  private String parentSettlementKey;
  private String detailLevel;
  private Integer contributesToAdjustment;
  private Long bomRowId;
  private String topProductCode;
  private String parentMaterialCode;
  private String materialCode;
  private String materialName;
  private String itemType;
  private BigDecimal quantity;
  private Long financePrepareItemId;
  private Long oaPrepareItemId;
  private BigDecimal financeUnitPrice;
  private BigDecimal oaUnitPrice;
  private BigDecimal financeAmount;
  private BigDecimal oaAmount;
  private BigDecimal diffAmount;
  private Integer cuAffected;
  private String priceFormulaRefType;
  private Long priceFormulaRefId;
  private String traceJson;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
