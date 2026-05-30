package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 供应商供货比例主数据。
 *
 * <p>该表不是价格源表，只负责描述同一物料多供应商之间的供货比例，取价时再用它选择比例最大的主供供应商。
 */
@Getter
@Setter
@TableName("lp_supplier_supply_ratio")
public class SupplierSupplyRatio {

  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String materialCode;
  private String materialName;
  private String specModel;
  private String unit;
  private String materialShape;
  private String supplierName;
  private String supplierCode;
  private BigDecimal supplyRatio;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String sourceType;
  private String sourceBatchNo;
  private String importFileName;
  private String importedBy;
  private LocalDateTime importedAt;

  @TableField(fill = FieldFill.INSERT)
  private String createdBy;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private String updatedBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  private Integer deleted;
}
