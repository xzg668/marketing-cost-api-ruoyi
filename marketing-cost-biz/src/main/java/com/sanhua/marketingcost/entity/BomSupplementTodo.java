package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** BOM 补录 OA 待办 / 通知记录实体。 */
@TableName("lp_bom_supplement_todo")
public class BomSupplementTodo {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long taskId;
  private String taskNo;
  private String todoNo;
  private String oaTodoId;
  private String todoStatus;
  private String pushStatus;
  private String pushErrorMessage;
  private String todoKind;
  private String recipientRole;
  private String assigneeName;
  private String title;
  private String todoUrl;
  private String oaTodoUrl;
  private String payloadJson;
  private LocalDateTime pushedAt;
  private LocalDateTime lastPushAt;
  private LocalDateTime closedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

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

  public String getTodoNo() {
    return todoNo;
  }

  public void setTodoNo(String todoNo) {
    this.todoNo = todoNo;
  }

  public String getOaTodoId() {
    return oaTodoId;
  }

  public void setOaTodoId(String oaTodoId) {
    this.oaTodoId = oaTodoId;
  }

  public String getTodoStatus() {
    return todoStatus;
  }

  public void setTodoStatus(String todoStatus) {
    this.todoStatus = todoStatus;
  }

  public String getPushStatus() {
    return pushStatus;
  }

  public void setPushStatus(String pushStatus) {
    this.pushStatus = pushStatus;
  }

  public String getPushErrorMessage() {
    return pushErrorMessage;
  }

  public void setPushErrorMessage(String pushErrorMessage) {
    this.pushErrorMessage = pushErrorMessage;
  }

  public String getTodoKind() {
    return todoKind;
  }

  public void setTodoKind(String todoKind) {
    this.todoKind = todoKind;
  }

  public String getRecipientRole() {
    return recipientRole;
  }

  public void setRecipientRole(String recipientRole) {
    this.recipientRole = recipientRole;
  }

  public String getAssigneeName() {
    return assigneeName;
  }

  public void setAssigneeName(String assigneeName) {
    this.assigneeName = assigneeName;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getTodoUrl() {
    return todoUrl;
  }

  public void setTodoUrl(String todoUrl) {
    this.todoUrl = todoUrl;
  }

  public String getOaTodoUrl() {
    return oaTodoUrl;
  }

  public void setOaTodoUrl(String oaTodoUrl) {
    this.oaTodoUrl = oaTodoUrl;
  }

  public String getPayloadJson() {
    return payloadJson;
  }

  public void setPayloadJson(String payloadJson) {
    this.payloadJson = payloadJson;
  }

  public LocalDateTime getPushedAt() {
    return pushedAt;
  }

  public void setPushedAt(LocalDateTime pushedAt) {
    this.pushedAt = pushedAt;
  }

  public LocalDateTime getLastPushAt() {
    return lastPushAt;
  }

  public void setLastPushAt(LocalDateTime lastPushAt) {
    this.lastPushAt = lastPushAt;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(LocalDateTime closedAt) {
    this.closedAt = closedAt;
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
