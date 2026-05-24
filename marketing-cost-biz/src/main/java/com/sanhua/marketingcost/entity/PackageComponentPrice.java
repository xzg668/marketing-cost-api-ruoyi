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

/** 包装组件月度价格主表。 */
@Getter
@Setter
@TableName("lp_package_component_price")
public class PackageComponentPrice {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long snapshotId;
  private String packageMaterialCode;
  private String packageMaterialName;
  private String periodMonth;
  /** 兼容旧库：部分环境未执行补 oa_no 迁移；该字段仅做请求上下文追溯，不参与价格唯一口径。 */
  @TableField(exist = false)
  private String oaNo;
  private String sourceTopProductCode;
  private String sourceBomPurpose;
  private String sourceBomSourceType;
  private BigDecimal totalPrice;
  private BigDecimal packageQtyPerParent;
  private BigDecimal packageQtyPerTop;
  private BigDecimal packageParentBaseQty;
  private String priceStatus;
  private Boolean priceComplete;
  private LocalDateTime generatedAt;
  private String calcBatchId;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
