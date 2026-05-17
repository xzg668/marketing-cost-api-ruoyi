package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** BOM 补录任务与报价单产品行关联实体。 */
@TableName("lp_bom_supplement_task_quote_link")
public class BomSupplementTaskQuoteLink {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long taskId;
  private String taskNo;
  private Long quoteBomStatusId;
  private Long oaFormId;
  private Long oaFormItemId;
  private String oaNo;
  private String productCode;
  private LocalDateTime createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getTaskId() {
    return taskId;
  }

  public void setTaskId(Long taskId) {
    this.taskId = taskId;
  }

  public String getTaskNo() {
    return taskNo;
  }

  public void setTaskNo(String taskNo) {
    this.taskNo = taskNo;
  }

  public Long getQuoteBomStatusId() {
    return quoteBomStatusId;
  }

  public void setQuoteBomStatusId(Long quoteBomStatusId) {
    this.quoteBomStatusId = quoteBomStatusId;
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

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
