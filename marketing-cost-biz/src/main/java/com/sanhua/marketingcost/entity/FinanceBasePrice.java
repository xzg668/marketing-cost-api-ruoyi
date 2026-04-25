package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_finance_base_price")
public class FinanceBasePrice {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String priceMonth;
  private Integer seq;
  private String factorName;
  private String shortName;
  private String factorCode;
  private String priceSource;
  private BigDecimal price;
  private String unit;
  private String linkType;
  /** 业务单元租户口径：COMMERCIAL / HOUSEHOLD（V22 补齐） */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  /** V25：上期原价（影响因素 10 "价格-原价" 列），便于价格对比提示。 */
  @TableField("price_original")
  private BigDecimal priceOriginal;

  /** V25：Excel 导入批次 ID（UUID），同一次 /import-excel 的所有行共享，支持按批次回滚。 */
  @TableField("import_batch_id")
  private String importBatchId;

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

  public String getPriceMonth() {
    return priceMonth;
  }

  public void setPriceMonth(String priceMonth) {
    this.priceMonth = priceMonth;
  }

  public Integer getSeq() {
    return seq;
  }

  public void setSeq(Integer seq) {
    this.seq = seq;
  }

  public String getFactorName() {
    return factorName;
  }

  public void setFactorName(String factorName) {
    this.factorName = factorName;
  }

  public String getShortName() {
    return shortName;
  }

  public void setShortName(String shortName) {
    this.shortName = shortName;
  }

  public String getFactorCode() {
    return factorCode;
  }

  public void setFactorCode(String factorCode) {
    this.factorCode = factorCode;
  }

  public String getPriceSource() {
    return priceSource;
  }

  public void setPriceSource(String priceSource) {
    this.priceSource = priceSource;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public String getLinkType() {
    return linkType;
  }

  public void setLinkType(String linkType) {
    this.linkType = linkType;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public BigDecimal getPriceOriginal() {
    return priceOriginal;
  }

  public void setPriceOriginal(BigDecimal priceOriginal) {
    this.priceOriginal = priceOriginal;
  }

  public String getImportBatchId() {
    return importBatchId;
  }

  public void setImportBatchId(String importBatchId) {
    this.importBatchId = importBatchId;
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
