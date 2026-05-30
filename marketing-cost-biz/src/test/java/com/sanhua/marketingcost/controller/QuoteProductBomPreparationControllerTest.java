package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncRequest;
import com.sanhua.marketingcost.dto.quotebom.OaTodoPushResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationBatchResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateResponse.TaskLink;
import com.sanhua.marketingcost.service.OaTodoPushService;
import com.sanhua.marketingcost.service.QuoteBomSupplementCollaborationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteProductBomPreparationControllerTest {

  private QuoteBomSupplementCollaborationService service;
  private QuoteProductBomPreparationService preparationService;
  private QuoteProductBomCostingBuildService costingBuildService;
  private OaTodoPushService oaTodoPushService;
  private QuoteProductBomPreparationController controller;

  @BeforeEach
  void setUp() {
    service = mock(QuoteBomSupplementCollaborationService.class);
    preparationService = mock(QuoteProductBomPreparationService.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    oaTodoPushService = mock(OaTodoPushService.class);
    controller = new QuoteProductBomPreparationController(
        service, preparationService, costingBuildService, oaTodoPushService);
  }

  @Test
  void prepareDelegatesToPreparationService() {
    QuoteProductBomPreparationPreview preview =
        new QuoteProductBomPreparationPreview(
            201L, 301L, 401L, 10L, "OA-001", "FIN-001", "NON_BARE", null,
            false, "2026-05", "READY", "NOT_SUBMITTED", true, false, false,
            "FORMAL_BOM", true, 2, null, null, false, 0, null, null, null, null,
            null, null, List.of(), List.of(), null, List.of(), List.of());
    when(preparationService.prepareByOaFormItem(10L)).thenReturn(preview);

    CommonResult<QuoteProductBomPreparationPreview> result = controller.prepare(10L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().ready()).isTrue();
    verify(preparationService).prepareByOaFormItem(10L);
  }

  @Test
  void batchPrepareDelegatesToPreparationService() {
    QuoteBomBatchSyncRequest request = new QuoteBomBatchSyncRequest();
    request.setOaFormItemIds(List.of(10L, 11L));
    QuoteProductBomPreparationBatchResult response =
        new QuoteProductBomPreparationBatchResult(2, 2, 1, 1, 0, List.of());
    when(preparationService.batchPrepare(List.of(10L, 11L))).thenReturn(response);

    CommonResult<QuoteProductBomPreparationBatchResult> result = controller.batchPrepare(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().needTechnicianTaskCount()).isEqualTo(1);
    verify(preparationService).batchPrepare(List.of(10L, 11L));
  }

  @Test
  void previewDelegatesToPreparationService() {
    QuoteProductBomPreparationPreview preview =
        new QuoteProductBomPreparationPreview(
            201L, 301L, 401L, 10L, "OA-001", "FIN-001", "BARE", "BARE-001",
            true, "2026-05", "READY", "APPROVED", true, false, false,
            "FORMAL_BOM", true, 2, "FIN-REF", "FIN-REF", true, 3, 501L, null,
            null, null, null, null, List.of(), List.of(), null, List.of(), List.of());
    when(preparationService.getPreparationPreview(10L)).thenReturn(preview);

    CommonResult<QuoteProductBomPreparationPreview> result = controller.preview(10L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().packageReferenceReady()).isTrue();
    verify(preparationService).getPreparationPreview(10L);
  }

  @Test
  void createTasksDelegatesToCollaborationService() {
    QuoteProductBomTaskCreateRequest request =
        new QuoteProductBomTaskCreateRequest(List.of(10L), 72);
    QuoteProductBomTaskCreateResponse response =
        new QuoteProductBomTaskCreateResponse(
            1,
            1,
            0,
            0,
            List.of(
                new TaskLink(
                    501L,
                    "QBP-001",
                    10L,
                    "OA-001",
                    "FIN-001",
                    "NON_BARE_FULL_BOM",
                    "TODO_PENDING",
                    "tok",
                    LocalDateTime.now().plusHours(72),
                    "/collaborate/bom-supplement?token=tok",
                    false)),
            List.of());
    when(service.createTasks(request)).thenReturn(response);

    CommonResult<QuoteProductBomTaskCreateResponse> result = controller.createTasks(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().createdTaskCount()).isEqualTo(1);
    assertThat(result.getData().tasks().get(0).collaborationUrl()).contains("token=tok");
    verify(service).createTasks(request);
  }

  @Test
  void buildCostingRowsDelegatesToBuildService() {
    when(costingBuildService.buildByOaFormItem(10L))
        .thenReturn(new QuoteBomCostingBuildResponse(
            201L, null, 10L, "OA-001", "FIN-001", "NON_BARE", "2026-05",
            "f_20260528_abcdef", 3, 3, 0, Map.of("RAW_PRODUCT_BOM", 3), List.of(),
            LocalDateTime.now()));

    CommonResult<QuoteBomCostingBuildResponse> result = controller.buildCostingRows(10L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().sourceRefsWritten()).isEqualTo(3);
    verify(costingBuildService).buildByOaFormItem(10L);
  }

  @Test
  void pushOaTodoDelegatesToPushService() {
    when(oaTodoPushService.pushBomSupplementTask(501L))
        .thenReturn(new OaTodoPushResponse(
            501L, "QBP-501", "OA-TODO-501", "/oa/todo/501", "PUSHED", null, LocalDateTime.now()));

    CommonResult<OaTodoPushResponse> result = controller.pushOaTodo(501L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().oaTodoId()).isEqualTo("OA-TODO-501");
    verify(oaTodoPushService).pushBomSupplementTask(501L);
  }
}
