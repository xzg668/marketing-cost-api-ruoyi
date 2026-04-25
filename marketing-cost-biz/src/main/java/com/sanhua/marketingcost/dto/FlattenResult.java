package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /api/v1/bom/flatten} 响应体。
 *
 * <p>字段语义（见 BOM 三层架构设计文档 §E.3）：
 * <ul>
 *   <li>{@link #costingRowsWritten}：本次 upsert 受影响的 costing 行数</li>
 *   <li>{@link #subtreeRequiredCount}：被打上 {@code subtree_cost_required=1} 的行数（下游走子树算法）</li>
 *   <li>{@link #warnings}：非致命告警，如 "部品联动类未配置对应的联动价" 之类</li>
 * </ul>
 */
public class FlattenResult {

  private int costingRowsWritten;
  private int subtreeRequiredCount;
  private List<String> warnings = new ArrayList<>();

  public int getCostingRowsWritten() {
    return costingRowsWritten;
  }

  public void setCostingRowsWritten(int costingRowsWritten) {
    this.costingRowsWritten = costingRowsWritten;
  }

  public int getSubtreeRequiredCount() {
    return subtreeRequiredCount;
  }

  public void setSubtreeRequiredCount(int subtreeRequiredCount) {
    this.subtreeRequiredCount = subtreeRequiredCount;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<String> warnings) {
    this.warnings = warnings;
  }
}
