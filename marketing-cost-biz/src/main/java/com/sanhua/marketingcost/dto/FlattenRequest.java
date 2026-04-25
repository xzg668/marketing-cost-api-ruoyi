package com.sanhua.marketingcost.dto;

import java.time.LocalDate;

/**
 * {@code POST /api/v1/bom/flatten} 请求体。
 *
 * <p>字段语义（见 BOM 三层架构设计文档 §E.3）：
 * <ul>
 *   <li>{@link #bomPurpose}：purpose 过滤（空=全部）</li>
 *   <li>{@link #mode}：{@code BY_OA}（绑定 OA 拍平，默认）/ {@code BY_PRODUCT}（按顶层产品） / {@code ALL}（该 asOfDate 的全部）</li>
 *   <li>{@link #oaNo}：mode=BY_OA 时必填；costing_row.oa_no 的来源</li>
 *   <li>{@link #topProductCode}：mode=BY_PRODUCT 时必填</li>
 *   <li>{@link #asOfDate}：<b>必填</b>——锁月复现核心；决定用哪个 effective 版本的 raw_hierarchy</li>
 * </ul>
 */
public class FlattenRequest {

  private String bomPurpose;
  private String mode;
  private String oaNo;
  private String topProductCode;
  private LocalDate asOfDate;

  public String getBomPurpose() {
    return bomPurpose;
  }

  public void setBomPurpose(String bomPurpose) {
    this.bomPurpose = bomPurpose;
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getTopProductCode() {
    return topProductCode;
  }

  public void setTopProductCode(String topProductCode) {
    this.topProductCode = topProductCode;
  }

  public LocalDate getAsOfDate() {
    return asOfDate;
  }

  public void setAsOfDate(LocalDate asOfDate) {
    this.asOfDate = asOfDate;
  }
}
