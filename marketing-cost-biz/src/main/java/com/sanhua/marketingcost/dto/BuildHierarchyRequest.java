package com.sanhua.marketingcost.dto;

/**
 * {@code POST /api/v1/bom/build-hierarchy} 请求体。
 *
 * <p>字段语义（见 BOM 三层架构设计文档 §E.2）：
 * <ul>
 *   <li>{@link #importBatchId}：T3 产生的批次 ID；Builder 从该批次的 u9_source 行读取</li>
 *   <li>{@link #bomPurpose}：过滤目的（普机/主制造/精益）；空=全部 purpose 都构建</li>
 *   <li>{@link #mode}：{@code ALL}=该批次全部顶层 / {@code BY_PRODUCT}=只构建一个顶层</li>
 *   <li>{@link #topProductCode}：mode=BY_PRODUCT 时必填；mode=ALL 时忽略</li>
 * </ul>
 */
public class BuildHierarchyRequest {

  private String importBatchId;
  private String bomPurpose;

  /** ALL / BY_PRODUCT */
  private String mode;

  private String topProductCode;

  public String getImportBatchId() {
    return importBatchId;
  }

  public void setImportBatchId(String importBatchId) {
    this.importBatchId = importBatchId;
  }

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

  public String getTopProductCode() {
    return topProductCode;
  }

  public void setTopProductCode(String topProductCode) {
    this.topProductCode = topProductCode;
  }
}
