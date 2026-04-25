package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /api/v1/bom/build-hierarchy} 响应体。
 *
 * <p>字段语义（见 BOM 三层架构设计文档 §E.2）：
 * <ul>
 *   <li>{@link #buildBatchId}：本次构建批次 UUID，写进每行 raw_hierarchy.build_batch_id</li>
 *   <li>{@link #productsProcessed}：成功构建的顶层产品数量</li>
 *   <li>{@link #rowsWritten}：本次写入（INSERT + ON DUPLICATE KEY UPDATE 命中）的行数</li>
 *   <li>{@link #failedProducts}：构建失败的顶层料号（环检测失败 / DFS 错等），已记 WARN 日志</li>
 * </ul>
 */
public class BuildHierarchyResult {

  private String buildBatchId;
  private int productsProcessed;
  private int rowsWritten;
  private List<String> failedProducts = new ArrayList<>();

  public String getBuildBatchId() {
    return buildBatchId;
  }

  public void setBuildBatchId(String buildBatchId) {
    this.buildBatchId = buildBatchId;
  }

  public int getProductsProcessed() {
    return productsProcessed;
  }

  public void setProductsProcessed(int productsProcessed) {
    this.productsProcessed = productsProcessed;
  }

  public int getRowsWritten() {
    return rowsWritten;
  }

  public void setRowsWritten(int rowsWritten) {
    this.rowsWritten = rowsWritten;
  }

  public List<String> getFailedProducts() {
    return failedProducts;
  }

  public void setFailedProducts(List<String> failedProducts) {
    this.failedProducts = failedProducts;
  }
}
