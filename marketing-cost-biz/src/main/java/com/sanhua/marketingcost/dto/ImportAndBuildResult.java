package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /api/v1/bom/import-and-build} 合成端点响应。
 *
 * <p>面向财务场景：一次动作完成"导入 + 所有 purpose 的 ALL 构建"，
 * 避免把三阶段流水线暴露给业务用户。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@link #importResult}：阶段 A 结果（批次 / 行数 / 错误）</li>
 *   <li>{@link #builds}：按 bomPurpose 分别触发一次 build ALL 的结果数组</li>
 *   <li>{@link #totalRawRowsWritten}：所有 purpose 汇总的 raw_hierarchy 写入行数</li>
 *   <li>{@link #purposesBuilt}：本次实际执行了 build 的 purpose 列表</li>
 *   <li>{@link #status}：{@code SUCCESS} / {@code PARTIAL_BUILD_FAILED} / {@code IMPORT_FAILED}</li>
 * </ul>
 */
public class ImportAndBuildResult {

  private BomImportResult importResult;
  private List<BuildHierarchyResult> builds = new ArrayList<>();
  private int totalRawRowsWritten;
  private List<String> purposesBuilt = new ArrayList<>();
  private String status;
  private String errorMessage;

  public BomImportResult getImportResult() {
    return importResult;
  }

  public void setImportResult(BomImportResult importResult) {
    this.importResult = importResult;
  }

  public List<BuildHierarchyResult> getBuilds() {
    return builds;
  }

  public void setBuilds(List<BuildHierarchyResult> builds) {
    this.builds = builds;
  }

  public int getTotalRawRowsWritten() {
    return totalRawRowsWritten;
  }

  public void setTotalRawRowsWritten(int totalRawRowsWritten) {
    this.totalRawRowsWritten = totalRawRowsWritten;
  }

  public List<String> getPurposesBuilt() {
    return purposesBuilt;
  }

  public void setPurposesBuilt(List<String> purposesBuilt) {
    this.purposesBuilt = purposesBuilt;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
