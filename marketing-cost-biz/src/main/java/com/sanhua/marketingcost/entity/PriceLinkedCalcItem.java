package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_price_linked_calc_item")
public class PriceLinkedCalcItem {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String oaNo;
  private String itemCode;
  private String shapeAttr;
  private BigDecimal bomQty;
  private BigDecimal partUnitPrice;
  private BigDecimal partAmount;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  /** LPE-02：联动价结果计算场景；历史 refresh 固定写 QUOTE，月度调价后续独立写 MONTHLY_ADJUST。 */
  private String calcScene;

  /** LPE-02：价格月份参与结果隔离，避免同一 OA/料号跨月份误读。 */
  private String pricingMonth;

  /** LPE-02：月度调价批次上下文，QUOTE 场景为空。 */
  private Long adjustBatchId;

  /** LPE-02：变量来源，QUOTE 默认为 OA_LOCKED，月度调价默认为 ADJUST_BATCH。 */
  private String factorSource;

  /** LPE-02：输入指纹，后续 ensure 用于判断结果是否过期。 */
  private String calcFingerprint;

  /** LPE-02：计算状态，OK / FAILED。 */
  private String calcStatus;

  /** LPE-02：计算失败或跳过说明。 */
  private String calcMessage;

  /** V26 计算 trace JSON —— 存 {normalizedExpr, variables, steps, result/error} */
  @TableField("trace_json")
  private String traceJson;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getCalcScene() {
    return calcScene;
  }

  public void setCalcScene(String calcScene) {
    this.calcScene = calcScene;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public void setPricingMonth(String pricingMonth) {
    this.pricingMonth = pricingMonth;
  }

  public Long getAdjustBatchId() {
    return adjustBatchId;
  }

  public void setAdjustBatchId(Long adjustBatchId) {
    this.adjustBatchId = adjustBatchId;
  }

  public String getFactorSource() {
    return factorSource;
  }

  public void setFactorSource(String factorSource) {
    this.factorSource = factorSource;
  }

  public String getCalcFingerprint() {
    return calcFingerprint;
  }

  public void setCalcFingerprint(String calcFingerprint) {
    this.calcFingerprint = calcFingerprint;
  }

  public String getCalcStatus() {
    return calcStatus;
  }

  public void setCalcStatus(String calcStatus) {
    this.calcStatus = calcStatus;
  }

  public String getCalcMessage() {
    return calcMessage;
  }

  public void setCalcMessage(String calcMessage) {
    this.calcMessage = calcMessage;
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

  public String getItemCode() {
    return itemCode;
  }

  public void setItemCode(String itemCode) {
    this.itemCode = itemCode;
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

  public BigDecimal getPartUnitPrice() {
    return partUnitPrice;
  }

  public void setPartUnitPrice(BigDecimal partUnitPrice) {
    this.partUnitPrice = partUnitPrice;
  }

  public BigDecimal getPartAmount() {
    return partAmount;
  }

  public void setPartAmount(BigDecimal partAmount) {
    this.partAmount = partAmount;
  }

  public String getTraceJson() {
    return traceJson;
  }

  public void setTraceJson(String traceJson) {
    this.traceJson = traceJson;
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
