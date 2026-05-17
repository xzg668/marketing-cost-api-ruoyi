package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;

/** 批量发起 BOM 补录 OA 任务响应。 */
public class BomSupplementBatchOaTaskResponse {
  private int requestedCount;
  private int createdTaskCount;
  private int reusedTaskCount;
  private int pushedTodoCount;
  private int rejectedCount;
  private List<BomSupplementTaskResult> tasks = new ArrayList<>();
  private List<BomSupplementRejectedRow> rejectedRows = new ArrayList<>();

  public int getRequestedCount() {
    return requestedCount;
  }

  public void setRequestedCount(int requestedCount) {
    this.requestedCount = requestedCount;
  }

  public int getCreatedTaskCount() {
    return createdTaskCount;
  }

  public void setCreatedTaskCount(int createdTaskCount) {
    this.createdTaskCount = createdTaskCount;
  }

  public int getReusedTaskCount() {
    return reusedTaskCount;
  }

  public void setReusedTaskCount(int reusedTaskCount) {
    this.reusedTaskCount = reusedTaskCount;
  }

  public int getPushedTodoCount() {
    return pushedTodoCount;
  }

  public void setPushedTodoCount(int pushedTodoCount) {
    this.pushedTodoCount = pushedTodoCount;
  }

  public int getRejectedCount() {
    return rejectedCount;
  }

  public void setRejectedCount(int rejectedCount) {
    this.rejectedCount = rejectedCount;
  }

  public List<BomSupplementTaskResult> getTasks() {
    return tasks;
  }

  public void setTasks(List<BomSupplementTaskResult> tasks) {
    this.tasks = tasks;
  }

  public List<BomSupplementRejectedRow> getRejectedRows() {
    return rejectedRows;
  }

  public void setRejectedRows(List<BomSupplementRejectedRow> rejectedRows) {
    this.rejectedRows = rejectedRows;
  }

  public static class BomSupplementTaskResult {
    private Long taskId;
    private String taskNo;
    private String productCode;
    private String technicianName;
    private String taskStatus;
    private boolean reused;
    private String todoNo;

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

    public String getProductCode() {
      return productCode;
    }

    public void setProductCode(String productCode) {
      this.productCode = productCode;
    }

    public String getTechnicianName() {
      return technicianName;
    }

    public void setTechnicianName(String technicianName) {
      this.technicianName = technicianName;
    }

    public String getTaskStatus() {
      return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
      this.taskStatus = taskStatus;
    }

    public boolean isReused() {
      return reused;
    }

    public void setReused(boolean reused) {
      this.reused = reused;
    }

    public String getTodoNo() {
      return todoNo;
    }

    public void setTodoNo(String todoNo) {
      this.todoNo = todoNo;
    }
  }

  public static class BomSupplementRejectedRow {
    private Long quoteBomStatusId;
    private String productCode;
    private String reason;

    public Long getQuoteBomStatusId() {
      return quoteBomStatusId;
    }

    public void setQuoteBomStatusId(Long quoteBomStatusId) {
      this.quoteBomStatusId = quoteBomStatusId;
    }

    public String getProductCode() {
      return productCode;
    }

    public void setProductCode(String productCode) {
      this.productCode = productCode;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }
}
