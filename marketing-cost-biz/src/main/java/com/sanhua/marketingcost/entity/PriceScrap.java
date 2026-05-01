package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 废料回收价 entity (V48) —— 对应 lp_price_scrap 表。
 *
 * <p>跟 lp_finance_base_price（金属基价、采购成本侧）互补：本表是废料回收价（业务收入侧）。
 * 自制件公式可引用 scrap_code 拿到 recycle_price 做"边角料抵扣"计算。
 */
@TableName("lp_price_scrap")
public class PriceScrap {
  @TableId(type = IdType.AUTO)
  private Long id;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  /** 结算期间 YYYY-MM */
  private String pricingMonth;

  /** 废料代号（业务唯一标识，如"废紫铜"/"废不锈钢SUS304"） */
  private String scrapCode;

  /** 废料名称 */
  private String scrapName;

  /** 规格型号 */
  private String specModel;

  /** 单位 */
  private String unit;

  /** 回收单价（业务收入侧，不是采购成本） */
  private BigDecimal recyclePrice;

  /** 是否含税：1 含税 / 0 不含税 */
  private Integer taxIncluded;

  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String remark;

  /** 软删除标记 —— 与全局 @TableLogic 对齐 */
  @TableLogic
  private Integer deleted;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }

  public String getBusinessUnitType() { return businessUnitType; }
  public void setBusinessUnitType(String businessUnitType) { this.businessUnitType = businessUnitType; }

  public String getPricingMonth() { return pricingMonth; }
  public void setPricingMonth(String pricingMonth) { this.pricingMonth = pricingMonth; }

  public String getScrapCode() { return scrapCode; }
  public void setScrapCode(String scrapCode) { this.scrapCode = scrapCode; }

  public String getScrapName() { return scrapName; }
  public void setScrapName(String scrapName) { this.scrapName = scrapName; }

  public String getSpecModel() { return specModel; }
  public void setSpecModel(String specModel) { this.specModel = specModel; }

  public String getUnit() { return unit; }
  public void setUnit(String unit) { this.unit = unit; }

  public BigDecimal getRecyclePrice() { return recyclePrice; }
  public void setRecyclePrice(BigDecimal recyclePrice) { this.recyclePrice = recyclePrice; }

  public Integer getTaxIncluded() { return taxIncluded; }
  public void setTaxIncluded(Integer taxIncluded) { this.taxIncluded = taxIncluded; }

  public LocalDate getEffectiveFrom() { return effectiveFrom; }
  public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

  public LocalDate getEffectiveTo() { return effectiveTo; }
  public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }

  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }

  public Integer getDeleted() { return deleted; }
  public void setDeleted(Integer deleted) { this.deleted = deleted; }

  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
