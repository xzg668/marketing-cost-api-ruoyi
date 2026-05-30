package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.OaTodoCallbackRequest;
import com.sanhua.marketingcost.entity.BomSupplementTask;
import com.sanhua.marketingcost.entity.BomSupplementTodo;
import com.sanhua.marketingcost.integration.oa.OaTodoGateway;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTodoMapper;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OaTodoPushServiceImplTest {

  private BomSupplementTaskMapper taskMapper;
  private BomSupplementTodoMapper todoMapper;
  private OaTodoGateway gateway;
  private OaTodoPushServiceImpl service;

  @BeforeEach
  void setUp() {
    taskMapper = mock(BomSupplementTaskMapper.class);
    todoMapper = mock(BomSupplementTodoMapper.class);
    gateway = mock(OaTodoGateway.class);
    service = new OaTodoPushServiceImpl(taskMapper, todoMapper, gateway);
  }

  @Test
  void pushSuccessWritesOaTodoFieldsAndMovesTaskToPushed() {
    BomSupplementTask task = task("TODO_PENDING");
    when(taskMapper.selectById(501L)).thenReturn(task);
    when(todoMapper.selectOne(any())).thenReturn(todo("NOT_PUSHED", null));
    when(gateway.push(any()))
        .thenReturn(new OaTodoGateway.PushResult(true, "OA-TODO-501", "/oa/todo/501", null));

    var response = service.pushBomSupplementTask(501L);

    assertThat(response.pushStatus()).isEqualTo("PUSHED");
    assertThat(response.oaTodoId()).isEqualTo("OA-TODO-501");
    assertThat(task.getTaskStatus()).isEqualTo("TODO_PUSHED");
    verify(taskMapper).updateById(task);
    ArgumentCaptor<BomSupplementTodo> captor = ArgumentCaptor.forClass(BomSupplementTodo.class);
    verify(todoMapper).updateById(captor.capture());
    assertThat(captor.getValue().getPushErrorMessage()).isNull();
    assertThat(captor.getValue().getLastPushAt()).isNotNull();
  }

  @Test
  void pushFailureKeepsRetryableErrorMessage() {
    when(taskMapper.selectById(501L)).thenReturn(task("TODO_PENDING"));
    when(todoMapper.selectOne(any())).thenReturn(todo("NOT_PUSHED", null));
    when(gateway.push(any()))
        .thenReturn(new OaTodoGateway.PushResult(false, null, null, "认证失败"));

    var response = service.pushBomSupplementTask(501L);

    assertThat(response.pushStatus()).isEqualTo("FAILED");
    assertThat(response.pushErrorMessage()).isEqualTo("认证失败");
    verify(todoMapper).updateById(any(BomSupplementTodo.class));
  }

  @Test
  void closeTodoMarksClosed() {
    when(taskMapper.selectById(501L)).thenReturn(task("TODO_PUSHED"));
    when(todoMapper.selectOne(any())).thenReturn(todo("PUSHED", "OA-TODO-501"));
    when(gateway.close("OA-TODO-501"))
        .thenReturn(new OaTodoGateway.StatusResult(true, "CLOSED", "OA-TODO-501", "/oa/todo/501", null));

    var response = service.closeTodo(501L);

    assertThat(response.pushStatus()).isEqualTo("CLOSED");
    verify(todoMapper).updateById(any(BomSupplementTodo.class));
  }

  @Test
  void callbackCanUpdateByOaTodoId() {
    BomSupplementTodo todo = todo("PUSHED", "OA-TODO-501");
    when(todoMapper.selectOne(any())).thenReturn(todo);
    when(taskMapper.selectById(501L)).thenReturn(task("TODO_PUSHED"));

    var response =
        service.handleCallback(new OaTodoCallbackRequest(null, null, "OA-TODO-501", "DONE", "完成", "技术员A"));

    assertThat(response.pushStatus()).isEqualTo("DONE");
    assertThat(todo.getClosedAt()).isNotNull();
    verify(todoMapper).updateById(todo);
  }

  @Test
  void missingTaskThrowsReadableError() {
    when(taskMapper.selectById(999L)).thenReturn(null);

    assertThatThrownBy(() -> service.pushBomSupplementTask(999L))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("BOM 补录任务不存在");
  }

  private BomSupplementTask task(String status) {
    BomSupplementTask task = new BomSupplementTask();
    task.setId(501L);
    task.setTaskNo("QBP-501");
    task.setTaskStatus(status);
    task.setProductCode("FIN-001");
    task.setTechnicianName("技术员A");
    task.setUpdatedAt(LocalDateTime.now());
    return task;
  }

  private BomSupplementTodo todo(String pushStatus, String oaTodoId) {
    BomSupplementTodo todo = new BomSupplementTodo();
    todo.setId(701L);
    todo.setTaskId(501L);
    todo.setTaskNo("QBP-501");
    todo.setTodoNo(oaTodoId == null ? "PENDING-QBP-501" : oaTodoId);
    todo.setOaTodoId(oaTodoId);
    todo.setTodoStatus(pushStatus);
    todo.setPushStatus(pushStatus);
    todo.setTodoKind("TODO");
    todo.setRecipientRole("TECHNICIAN");
    todo.setAssigneeName("技术员A");
    todo.setTitle("请处理报价产品 FIN-001 的 BOM 准备任务");
    return todo;
  }
}
