package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 联动价行局部变量绑定 —— 把公式里的 B 组 token（材料含税价格/废料含税价格/材料价格/废料价格）
 * 映射到具体的影响因素（{@code lp_price_variable.variable_code}）。
 *
 * <p>背景：Excel「联动价-部品6」里多行使用相同字面 token「材料含税价格」，
 * 但每行实际指向不同的影响因素（钢板 DC01 / SUS304 / Cu / ...）。这一行局部映射
 * 由供管部提供，系统用本表独立承载，不和全局变量字典混用。
 *
 * <p>版本化：{@code effective_date} / {@code expiry_date} 按月切片；
 * 修改绑定时旧行置 {@code expiry_date = new.effective_date - 1 day} 并插入新行。
 *
 * <p>来源：
 * <ul>
 *   <li>{@code EXCEL_INFERRED} —— V34 seed 推断，需要供管部确认；</li>
 *   <li>{@code SUPPLY_CONFIRMED} —— 供管部 CSV 正式导入；</li>
 *   <li>{@code MANUAL} —— 后台手工维护。</li>
 * </ul>
 *
 * <p>唯一键 {@code (linked_item_id, token_name, effective_date, deleted)}
 * —— 同一行同一 token 同一生效日只能有一条未删记录。
 */
@TableName("lp_price_variable_binding")
public class PriceVariableBinding {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** 关联 {@code lp_price_linked_item.id} */
  private Long linkedItemId;

  /** B 组 token 字面值：材料含税价格/材料价格/废料含税价格/废料价格 */
  private String tokenName;

  /** 指向 {@code lp_price_variable.variable_code}；evaluator 按这个递归求值 */
  private String factorCode;

  /** 价源：平均价/出厂价/招标价/采购价/现货价/月均价；null 表示"由被指变量自身决定" */
  private String priceSource;

  /** 是否按 BU 隔离查询财务基价；默认 1（开启）。0 表示全公司共享 */
  private Integer buScoped;

  /** 生效日期（含），按月切片 */
  private LocalDate effectiveDate;

  /** 失效日期（含）；null 表示当前生效版本 */
  private LocalDate expiryDate;

  /** 来源：EXCEL_INFERRED / SUPPLY_CONFIRMED / MANUAL */
  private String source;

  /** 确认人（SUPPLY_CONFIRMED 时填写） */
  private String confirmedBy;

  /** 确认时间 */
  private LocalDateTime confirmedAt;

  /** 备注：供管部批次号/人工修正原因/任何 CSV 多出来的列 */
  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private String createdBy;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private String updatedBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  /** MyBatis Plus 软删除：0=未删 / 1=已删 */
  @TableLogic
  private Integer deleted;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getLinkedItemId() {
    return linkedItemId;
  }

  public void setLinkedItemId(Long linkedItemId) {
    this.linkedItemId = linkedItemId;
  }

  public String getTokenName() {
    return tokenName;
  }

  public void setTokenName(String tokenName) {
    this.tokenName = tokenName;
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

  public Integer getBuScoped() {
    return buScoped;
  }

  public void setBuScoped(Integer buScoped) {
    this.buScoped = buScoped;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(LocalDate effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  public LocalDate getExpiryDate() {
    return expiryDate;
  }

  public void setExpiryDate(LocalDate expiryDate) {
    this.expiryDate = expiryDate;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getConfirmedBy() {
    return confirmedBy;
  }

  public void setConfirmedBy(String confirmedBy) {
    this.confirmedBy = confirmedBy;
  }

  public LocalDateTime getConfirmedAt() {
    return confirmedAt;
  }

  public void setConfirmedAt(LocalDateTime confirmedAt) {
    this.confirmedAt = confirmedAt;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
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
