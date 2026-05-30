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
 * 部品/原材料-废料映射（V25 引入，V69 扩展）—— 联动价派生变量和自制件 CMS 废料取价的映射依据。
 *
 * <p>业务语义：本表维护当前有效的 {@code materialCode -> scrapCode} 映射。同一个原材料可以对应多个
 * CMS 回收废料料号，CMS 期间字段只做追溯，不参与自制件匹配。
 */
@Getter
@Setter
@TableName("lp_material_scrap_ref")
public class MaterialScrapRef {
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 部品或原材料代码 */
  @TableField("material_code")
  private String materialCode;

  /** 原材料展示字段，来自 CMS 映射源行；计算匹配仍只认 materialCode。 */
  private String materialName;
  private String materialSpec;
  private String materialUnit;

  /** 对应废料代码（CMS 体系）—— 后续废料当前价按这个编码取。 */
  @TableField("scrap_code")
  private String scrapCode;

  /** 回收废料展示字段，来自 CMS 映射源行；价格维护仍以 scrapCode 为唯一键。 */
  private String scrapName;
  private String scrapSpec;
  private String scrapUnit;

  /** 抵减比例（如铜沫 0.92），派生结果 = 废料 finance 价 × ratio */
  private BigDecimal ratio;

  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  /** CMS 映射来源追溯字段；期间字段只追溯，不参与自制件取价匹配。 */
  private String sourceType;
  private String sourceDocNo;
  private String cmsRecordId;
  private String linkDetailId;
  private String cmsPostingPeriod;
  private LocalDate cmsEffectiveDate;
  private LocalDate approvalTime;
  private LocalDate syncTime;
  private String remark;

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
