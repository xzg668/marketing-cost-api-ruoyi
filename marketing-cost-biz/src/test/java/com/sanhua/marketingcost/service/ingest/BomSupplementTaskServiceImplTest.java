package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskRequest;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskResponse;
import com.sanhua.marketingcost.entity.BomSupplementTask;
import com.sanhua.marketingcost.entity.BomSupplementTaskQuoteLink;
import com.sanhua.marketingcost.entity.BomSupplementTodo;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTaskQuoteLinkMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTodoMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BomSupplementTaskServiceImplTest {
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private OaFormItemMapper oaFormItemMapper;
  private BomSupplementTaskMapper taskMapper;
  private BomSupplementTaskQuoteLinkMapper linkMapper;
  private BomSupplementTodoMapper todoMapper;
  private BomSupplementTaskServiceImpl service;

  @BeforeEach
  void setUp() {
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    taskMapper = mock(BomSupplementTaskMapper.class);
    linkMapper = mock(BomSupplementTaskQuoteLinkMapper.class);
    todoMapper = mock(BomSupplementTodoMapper.class);
    service =
        new BomSupplementTaskServiceImpl(
            quoteBomStatusMapper, oaFormItemMapper, taskMapper, linkMapper, todoMapper);
    doAnswer(
            invocation -> {
              BomSupplementTask task = invocation.getArgument(0);
              task.setId(9001L);
              return 1;
            })
        .when(taskMapper)
        .insert(any(BomSupplementTask.class));
  }

  @Test
  void createsTaskLinkAndMockTodosForNoBomRow() {
    QuoteBomStatus status = status(3001L, "NO_BOM", "MAT-1001", "张三");
    when(quoteBomStatusMapper.selectList(any())).thenReturn(List.of(status));
    when(oaFormItemMapper.selectById(10L)).thenReturn(item("COMMERCIAL", "产品A"));

    BomSupplementBatchOaTaskResponse response = service.batchCreateAndMockPush(request(3001L));

    assertThat(response.getCreatedTaskCount()).isEqualTo(1);
    assertThat(response.getPushedTodoCount()).isEqualTo(1);
    assertThat(response.getRejectedCount()).isZero();
    assertThat(response.getTasks().get(0).getTodoNo()).startsWith("MOCK-OA-TODO-");
    assertThat(status.getBomStatus()).isEqualTo("ENTRY_IN_PROGRESS");
    assertThat(status.getManualTaskNo()).startsWith("BST-");
    assertThat(status.getSupplementTaskId()).isEqualTo(9001L);
    assertThat(status.getCheckedAt()).isNotNull();
    verify(taskMapper).insert(any(BomSupplementTask.class));
    verify(linkMapper).insert(any(BomSupplementTaskQuoteLink.class));
    verify(todoMapper, org.mockito.Mockito.times(2)).insert(any(BomSupplementTodo.class));
    verify(quoteBomStatusMapper).updateById(status);
  }

  @Test
  void reusesActiveTaskForSameProduct() {
    QuoteBomStatus status = status(3002L, "NO_BOM", "MAT-1002", "李四");
    BomSupplementTask existing = new BomSupplementTask();
    existing.setId(8001L);
    existing.setTaskNo("BST-EXISTING");
    existing.setProductCode("MAT-1002");
    existing.setTaskStatus("TODO_PUSHED");
    existing.setTechnicianName("李四");
    BomSupplementTodo existingTodo = new BomSupplementTodo();
    existingTodo.setTodoNo("MOCK-OA-TODO-BST-EXISTING");
    when(quoteBomStatusMapper.selectList(any())).thenReturn(List.of(status));
    when(oaFormItemMapper.selectById(10L)).thenReturn(item("COMMERCIAL", "产品A"));
    when(taskMapper.selectOne(any())).thenReturn(existing);
    when(todoMapper.selectOne(any())).thenReturn(existingTodo);

    BomSupplementBatchOaTaskResponse response = service.batchCreateAndMockPush(request(3002L));

    assertThat(response.getCreatedTaskCount()).isZero();
    assertThat(response.getReusedTaskCount()).isEqualTo(1);
    assertThat(response.getTasks().get(0).isReused()).isTrue();
    verify(taskMapper, never()).insert(any(BomSupplementTask.class));
  }

  @Test
  void rejectsSyncedRows() {
    QuoteBomStatus status = status(3003L, "SYNCED", "MAT-1003", "王五");
    when(quoteBomStatusMapper.selectList(any())).thenReturn(List.of(status));

    BomSupplementBatchOaTaskResponse response = service.batchCreateAndMockPush(request(3003L));

    assertThat(response.getRejectedCount()).isEqualTo(1);
    assertThat(response.getRejectedRows().get(0).getReason()).contains("无 BOM");
    verify(taskMapper, never()).insert(any(BomSupplementTask.class));
  }

  @Test
  void rejectsRowsWithoutTechnician() {
    QuoteBomStatus status = status(3004L, "NO_BOM", "MAT-1004", null);
    when(quoteBomStatusMapper.selectList(any())).thenReturn(List.of(status));

    BomSupplementBatchOaTaskResponse response = service.batchCreateAndMockPush(request(3004L));

    assertThat(response.getRejectedCount()).isEqualTo(1);
    assertThat(response.getRejectedRows().get(0).getReason()).contains("技术员为空");
    verify(taskMapper, never()).insert(any(BomSupplementTask.class));
  }

  @Test
  void createsTechnicianTodoAndQuoteOwnerNotice() {
    QuoteBomStatus status = status(3005L, "NO_BOM", "MAT-1005", "赵六");
    when(quoteBomStatusMapper.selectList(any())).thenReturn(List.of(status));
    when(oaFormItemMapper.selectById(10L)).thenReturn(item("COMMERCIAL", "产品B"));
    ArgumentCaptor<BomSupplementTodo> captor = ArgumentCaptor.forClass(BomSupplementTodo.class);

    service.batchCreateAndMockPush(request(3005L));

    verify(todoMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(BomSupplementTodo::getRecipientRole)
        .containsExactly("TECHNICIAN", "QUOTE_OWNER");
  }

  private BomSupplementBatchOaTaskRequest request(Long id) {
    BomSupplementBatchOaTaskRequest request = new BomSupplementBatchOaTaskRequest();
    request.setQuoteBomStatusIds(List.of(id));
    request.setRemark("OA报价单无BOM，请补录");
    return request;
  }

  private QuoteBomStatus status(Long id, String bomStatus, String productCode, String technicianName) {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setId(id);
    status.setOaFormId(1L);
    status.setOaFormItemId(10L);
    status.setOaNo("OA-T15-001");
    status.setProductCode(productCode);
    status.setProductModel("SHF-A");
    status.setBomStatus(bomStatus);
    status.setTechnicianName(technicianName);
    return status;
  }

  private OaFormItem item(String businessUnitType, String productName) {
    OaFormItem item = new OaFormItem();
    item.setId(10L);
    item.setBusinessUnitType(businessUnitType);
    item.setProductName(productName);
    return item;
  }
}
