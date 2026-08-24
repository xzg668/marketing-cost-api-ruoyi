package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeCandidateResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeGroupResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionHistoryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSummaryResponse;
import com.sanhua.marketingcost.service.QuoteBomAlternativeApplicationService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeFeatureSwitch;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class QuoteBomAlternativeControllerTest {

  private QuoteBomAlternativeApplicationService service;
  private QuoteBomAlternativeController controller;

  @BeforeEach
  void setUp() {
    service = mock(QuoteBomAlternativeApplicationService.class);
    controller =
        new QuoteBomAlternativeController(
            service,
            new QuoteBomAlternativeErrorMapper(),
            new QuoteBomAlternativeFeatureSwitch(true));
  }

  @Test
  void queriesCurrentAuthoritativeGroups() {
    QuoteBomAlternativeSummaryResponse summary =
        new QuoteBomAlternativeSummaryResponse(
            "2026-07",
            1,
            0,
            false,
            List.of(group()));
    when(service.getAlternativeGroups("OA-QBA-09", 901L, "2026-07"))
        .thenReturn(summary);

    CommonResult<QuoteBomAlternativeSummaryResponse> result =
        controller.getAlternativeGroups(
            "OA-QBA-09", 901L, "2026-07");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().groupCount()).isEqualTo(1);
    assertThat(result.getData().groups().getFirst().candidates())
        .extracting(QuoteBomAlternativeCandidateResponse::materialCode)
        .containsExactly("STD", "ALT");
    verify(service)
        .getAlternativeGroups("OA-QBA-09", 901L, "2026-07");
  }

  @Test
  void reportsFeatureSwitchStateAndRejectsNewSelectionWhenDisabled() {
    controller =
        new QuoteBomAlternativeController(
            service,
            new QuoteBomAlternativeErrorMapper(),
            new QuoteBomAlternativeFeatureSwitch(false));

    assertThat(controller.getFeatureStatus().getData().enabled()).isFalse();

    QuoteBomAlternativeSelectionRequest request =
        new QuoteBomAlternativeSelectionRequest(
            "2026-07", "ALT", 1, "BUILD-1", null);
    CommonResult<QuoteBomAlternativeSelectionResponse> result =
        controller.saveSelection(
            "OA-QBA-14", 1401L, "GROUP", request, null);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(409);
    assertThat(result.getMsg()).contains("ALT_SELECTION_DISABLED");
    verify(service, org.mockito.Mockito.never())
        .saveSelection(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void savesSelectionWithAuthenticatedOperatorAndReturnsRebuildResult() {
    QuoteBomAlternativeSelectionRequest request =
        new QuoteBomAlternativeSelectionRequest(
            "2026-07", "ALT", 1, "BUILD-1", "报价要求");
    Authentication authentication = mock(Authentication.class);
    when(authentication.getName()).thenReturn("quoter");
    QuoteBomAlternativeSelectionResponse response =
        new QuoteBomAlternativeSelectionResponse(
            "GROUP",
            2,
            "ALT",
            "ALTERNATIVE",
            "MANUAL_ALTERNATIVE",
            false,
            true,
            List.of(
                "PRICE_TYPE_CONFIRMATION",
                "PRICE_PREPARE",
                "FINAL_PRICE",
                "COST_RUN"));
    when(
            service.saveSelection(
                "OA-QBA-09", 901L, "GROUP", request, "quoter"))
        .thenReturn(response);

    CommonResult<QuoteBomAlternativeSelectionResponse> result =
        controller.saveSelection(
            "OA-QBA-09",
            901L,
            "GROUP",
            request,
            authentication);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().selectedChildType())
        .isEqualTo("ALTERNATIVE");
    assertThat(result.getData().workflowInvalidated())
        .containsExactly(
            "PRICE_TYPE_CONFIRMATION",
            "PRICE_PREPARE",
            "FINAL_PRICE",
            "COST_RUN");
    verify(service)
        .saveSelection(
            "OA-QBA-09", 901L, "GROUP", request, "quoter");
  }

  @Test
  void samePutEndpointRestoresStandardAndKeepsVersionHistory() {
    QuoteBomAlternativeSelectionRequest request =
        new QuoteBomAlternativeSelectionRequest(
            "2026-07", "STD", 2, "BUILD-2", "恢复标准件");
    QuoteBomAlternativeSelectionResponse response =
        new QuoteBomAlternativeSelectionResponse(
            "GROUP",
            3,
            "STD",
            "STANDARD",
            "MANUAL_STANDARD",
            false,
            true,
            List.of("PRICE_TYPE_CONFIRMATION", "PRICE_PREPARE", "FINAL_PRICE", "COST_RUN"));
    when(
            service.saveSelection(
                "OA-QBA-09", 901L, "GROUP", request, "system"))
        .thenReturn(response);

    CommonResult<QuoteBomAlternativeSelectionResponse> result =
        controller.saveSelection(
            "OA-QBA-09", 901L, "GROUP", request, null);

    assertThat(result.getData().selectionSource())
        .isEqualTo("MANUAL_STANDARD");
    assertThat(result.getData().selectionVersion()).isEqualTo(3);
  }

  @Test
  void queriesOrderedSelectionHistory() {
    List<QuoteBomAlternativeSelectionHistoryResponse> history =
        List.of(
            new QuoteBomAlternativeSelectionHistoryResponse(
                "SEL-1",
                "GROUP",
                1,
                "STD",
                "STD",
                "STANDARD",
                "AUTO_STANDARD",
                "ACTIVE",
                "system",
                LocalDateTime.of(2026, 7, 30, 9, 0),
                "系统首次默认标准件",
                "{\"candidates\":[]}",
                "IMPORT-1",
                "BUILD-1",
                false));
    when(service.getSelectionHistory(
            "OA-QBA-09", 901L, "GROUP", "2026-07"))
        .thenReturn(history);

    CommonResult<List<QuoteBomAlternativeSelectionHistoryResponse>>
        result =
            controller.getSelectionHistory(
                "OA-QBA-09", 901L, "GROUP", "2026-07");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).hasSize(1);
    assertThat(result.getData().getFirst().selectedBy())
        .isEqualTo("system");
  }

  @Test
  void rejectsMaterialOutsideCurrentCandidateGroupWithStableError() {
    QuoteBomAlternativeSelectionRequest request =
        new QuoteBomAlternativeSelectionRequest(
            "2026-07", "ARBITRARY", 1, "BUILD-1", null);
    when(
            service.saveSelection(
                "OA-QBA-09", 901L, "GROUP", request, "system"))
        .thenThrow(
            new QuoteBomAlternativeSelectionException(
                "ALT_CANDIDATE_INVALID",
                "所选料号不属于当前替代组"));

    CommonResult<QuoteBomAlternativeSelectionResponse> result =
        controller.saveSelection(
            "OA-QBA-09", 901L, "GROUP", request, null);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(400);
    assertThat(result.getMsg())
        .contains("ALT_CANDIDATE_INVALID")
        .contains("不属于当前替代组");
  }

  @Test
  void distinguishesIdempotentRepeatFromVersionConflict() {
    QuoteBomAlternativeSelectionRequest request =
        new QuoteBomAlternativeSelectionRequest(
            "2026-07", "STD", 1, "BUILD-1", null);
    when(
            service.saveSelection(
                "OA-QBA-09", 901L, "GROUP", request, "system"))
        .thenReturn(
            new QuoteBomAlternativeSelectionResponse(
                "GROUP",
                1,
                "STD",
                "STANDARD",
                "AUTO_STANDARD",
                true,
                false,
                List.of()));

    CommonResult<QuoteBomAlternativeSelectionResponse> idempotent =
        controller.saveSelection(
            "OA-QBA-09", 901L, "GROUP", request, null);

    assertThat(idempotent.isSuccess()).isTrue();
    assertThat(idempotent.getData().idempotent()).isTrue();
    assertThat(idempotent.getData().recalculationRequired()).isFalse();

    when(
            service.saveSelection(
                "OA-QBA-09", 901L, "GROUP", request, "system"))
        .thenThrow(
            new QuoteBomAlternativeSelectionException(
                "ALT_SELECTION_CONFLICT", "选择版本已变化"));

    CommonResult<QuoteBomAlternativeSelectionResponse> conflict =
        controller.saveSelection(
            "OA-QBA-09", 901L, "GROUP", request, null);

    assertThat(conflict.getCode()).isEqualTo(409);
    assertThat(conflict.getMsg()).contains("ALT_SELECTION_CONFLICT");
  }

  private static QuoteBomAlternativeGroupResponse group() {
    return new QuoteBomAlternativeGroupResponse(
        "GROUP",
        "PARENT",
        "父件",
        "/TOP/PARENT@10/",
        10,
        "010",
        "主制造",
        "V1",
        1,
        "AUTO_STANDARD",
        "ACTIVE",
        "STD",
        "STANDARD",
        "BUILD-1",
        false,
        true,
        List.of(
            new QuoteBomAlternativeCandidateResponse(
                "STD",
                "标准件",
                "S",
                "STANDARD",
                BigDecimal.ONE,
                "IMPORT-1",
                "BUILD-1",
                true),
            new QuoteBomAlternativeCandidateResponse(
                "ALT",
                "替代件",
                "A",
                "ALTERNATIVE",
                BigDecimal.ONE,
                "IMPORT-1",
                "BUILD-1",
                false)));
  }
}
