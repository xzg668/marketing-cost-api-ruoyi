package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_bom_manage_item")
public class BomManageItem {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String oaNo;
  private Long oaFormId;
  private Long oaFormItemId;
  private String materialNo;
  private String productName;
  private String productSpec;
  private String productModel;
  private String customerName;
  private BigDecimal copperPriceTax;
  private BigDecimal zincPriceTax;
  private BigDecimal aluminumPriceTax;
  private BigDecimal steelPriceTax;
  private String bomCode;
  private String rootItemCode;
  private String itemCode;
  private String itemName;
  private String itemSpec;
  private String itemModel;
  private String shapeAttr;
  private BigDecimal bomQty;
  private String material;
  private String source;
  private String filterRule;

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

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public Long getOaFormId() {
    return oaFormId;
  }

  public void setOaFormId(Long oaFormId) {
    this.oaFormId = oaFormId;
  }

  public Long getOaFormItemId() {
    return oaFormItemId;
  }

  public void setOaFormItemId(Long oaFormItemId) {
    this.oaFormItemId = oaFormItemId;
  }

  public String getMaterialNo() {
    return materialNo;
  }

  public void setMaterialNo(String materialNo) {
    this.materialNo = materialNo;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getProductSpec() {
    return productSpec;
  }

  public void setProductSpec(String productSpec) {
    this.productSpec = productSpec;
  }

  public String getProductModel() {
    return productModel;
  }

  public void setProductModel(String productModel) {
    this.productModel = productModel;
  }

  public String getCustomerName() {
    return customerName;
  }

  public void setCustomerName(String customerName) {
    this.customerName = customerName;
  }

  public BigDecimal getCopperPriceTax() {
    return copperPriceTax;
  }

  public void setCopperPriceTax(BigDecimal copperPriceTax) {
    this.copperPriceTax = copperPriceTax;
  }

  public BigDecimal getZincPriceTax() {
    return zincPriceTax;
  }

  public void setZincPriceTax(BigDecimal zincPriceTax) {
    this.zincPriceTax = zincPriceTax;
  }

  public BigDecimal getAluminumPriceTax() {
    return aluminumPriceTax;
  }

  public void setAluminumPriceTax(BigDecimal aluminumPriceTax) {
    this.aluminumPriceTax = aluminumPriceTax;
  }

  public BigDecimal getSteelPriceTax() {
    return steelPriceTax;
  }

  public void setSteelPriceTax(BigDecimal steelPriceTax) {
    this.steelPriceTax = steelPriceTax;
  }

  public String getBomCode() {
    return bomCode;
  }

  public void setBomCode(String bomCode) {
    this.bomCode = bomCode;
  }

  public String getRootItemCode() {
    return rootItemCode;
  }

  public void setRootItemCode(String rootItemCode) {
    this.rootItemCode = rootItemCode;
  }

  public String getItemCode() {
    return itemCode;
  }

  public void setItemCode(String itemCode) {
    this.itemCode = itemCode;
  }

  public String getItemName() {
    return itemName;
  }

  public void setItemName(String itemName) {
    this.itemName = itemName;
  }

  public String getItemSpec() {
    return itemSpec;
  }

  public void setItemSpec(String itemSpec) {
    this.itemSpec = itemSpec;
  }

  public String getItemModel() {
    return itemModel;
  }

  public void setItemModel(String itemModel) {
    this.itemModel = itemModel;
  }

  public String getShapeAttr() {
    return shapeAttr;
  }

  public void setShapeAttr(String shapeAttr) {
    this.shapeAttr = shapeAttr;
  }

  public BigDecimal getBomQty() {
    return bomQty;
  }

  public void setBomQty(BigDecimal bomQty) {
    this.bomQty = bomQty;
  }

  public String getMaterial() {
    return material;
  }

  public void setMaterial(String material) {
    this.material = material;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getFilterRule() {
    return filterRule;
  }

  public void setFilterRule(String filterRule) {
    this.filterRule = filterRule;
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
