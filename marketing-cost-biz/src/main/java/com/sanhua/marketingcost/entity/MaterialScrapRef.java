package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 部品-废料映射（V25 引入）—— 联动价派生变量 {@code scrap_price_incl} 取值依据。
 *
 * <p>业务语义：一个部品在加工后会产生废料（铜/铝/锌等），废料按比例折算回收价抵减原材料成本；
 * 本表维护 {@code materialCode → scrapCode + ratio} 的映射，废料价格走财务基价表。
 */
@TableName("lp_material_scrap_ref")
public class MaterialScrapRef {
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 部品或原材料代码 */
  @TableField("material_code")
  private String materialCode;

  /** 对应废料代码（CMS 体系）—— 财务基价表 short_name/factor_code 命中键 */
  @TableField("scrap_code")
  private String scrapCode;

  /** 抵减比例（如铜沫 0.92），派生结果 = 废料 finance 价 × ratio */
  private BigDecimal ratio;

  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getScrapCode() {
    return scrapCode;
  }

  public void setScrapCode(String scrapCode) {
    this.scrapCode = scrapCode;
  }

  public BigDecimal getRatio() {
    return ratio;
  }

  public void setRatio(BigDecimal ratio) {
    this.ratio = ratio;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
