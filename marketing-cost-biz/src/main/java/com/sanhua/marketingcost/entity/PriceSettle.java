package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_price_settle")
public class PriceSettle {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String buyer;
  private String seller;
  private String businessType;
  private String productProperty;
  private BigDecimal copperPrice;
  private String month;
  private String approvalContent;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getBuyer() { return buyer; }
  public void setBuyer(String buyer) { this.buyer = buyer; }
  public String getSeller() { return seller; }
  public void setSeller(String seller) { this.seller = seller; }
  public String getBusinessType() { return businessType; }
  public void setBusinessType(String businessType) { this.businessType = businessType; }
  public String getProductProperty() { return productProperty; }
  public void setProductProperty(String productProperty) { this.productProperty = productProperty; }
  public BigDecimal getCopperPrice() { return copperPrice; }
  public void setCopperPrice(BigDecimal copperPrice) { this.copperPrice = copperPrice; }
  public String getMonth() { return month; }
  public void setMonth(String month) { this.month = month; }
  public String getApprovalContent() { return approvalContent; }
  public void setApprovalContent(String approvalContent) { this.approvalContent = approvalContent; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
