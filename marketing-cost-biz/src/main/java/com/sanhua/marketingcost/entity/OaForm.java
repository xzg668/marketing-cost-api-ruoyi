package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("oa_form")
public class OaForm {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String oaNo;
  private String formType;
  private LocalDate applyDate;
  private String customer;
  private BigDecimal copperPrice;
  private BigDecimal zincPrice;
  private BigDecimal aluminumPrice;
  private BigDecimal steelPrice;
  private BigDecimal otherMaterial;
  private BigDecimal baseShipping;
  private String calcStatus;
  private String saleLink;
  private String remark;

  /** V21 业务单元数据隔离：COMMERCIAL 商用 / HOUSEHOLD 家用 */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  @TableLogic
  private Integer deleted;

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getFormType() {
    return formType;
  }

  public void setFormType(String formType) {
    this.formType = formType;
  }

  public LocalDate getApplyDate() {
    return applyDate;
  }

  public void setApplyDate(LocalDate applyDate) {
    this.applyDate = applyDate;
  }

  public String getCustomer() {
    return customer;
  }

  public void setCustomer(String customer) {
    this.customer = customer;
  }

  public BigDecimal getCopperPrice() {
    return copperPrice;
  }

  public void setCopperPrice(BigDecimal copperPrice) {
    this.copperPrice = copperPrice;
  }

  public BigDecimal getZincPrice() {
    return zincPrice;
  }

  public void setZincPrice(BigDecimal zincPrice) {
    this.zincPrice = zincPrice;
  }

  public BigDecimal getAluminumPrice() {
    return aluminumPrice;
  }

  public void setAluminumPrice(BigDecimal aluminumPrice) {
    this.aluminumPrice = aluminumPrice;
  }

  public BigDecimal getSteelPrice() {
    return steelPrice;
  }

  public void setSteelPrice(BigDecimal steelPrice) {
    this.steelPrice = steelPrice;
  }

  public BigDecimal getOtherMaterial() {
    return otherMaterial;
  }

  public void setOtherMaterial(BigDecimal otherMaterial) {
    this.otherMaterial = otherMaterial;
  }

  public BigDecimal getBaseShipping() {
    return baseShipping;
  }

  public void setBaseShipping(BigDecimal baseShipping) {
    this.baseShipping = baseShipping;
  }

  public String getCalcStatus() {
    return calcStatus;
  }

  public void setCalcStatus(String calcStatus) {
    this.calcStatus = calcStatus;
  }

  public String getSaleLink() {
    return saleLink;
  }

  public void setSaleLink(String saleLink) {
    this.saleLink = saleLink;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
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

  public Integer getDeleted() {
    return deleted;
  }

  public void setDeleted(Integer deleted) {
    this.deleted = deleted;
  }
}
