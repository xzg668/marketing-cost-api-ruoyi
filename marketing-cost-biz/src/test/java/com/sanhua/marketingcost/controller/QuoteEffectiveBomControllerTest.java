package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomExclusionSummaryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomAlternativePreviewRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomControllerTest {

  private QuoteEffectiveBomApplicationService service;
  private QuoteEffectiveBomController controller;

  @BeforeEach
  void setUp() {
    service = mock(QuoteEffectiveBomApplicationService.class);
    controller =
        new QuoteEffectiveBomController(service, new QuoteEffectiveBomErrorMapper());
  }

  @Test
  void queriesExactlyOneOaProductLine() {
    when(service.getEffectiveBom("OA-1", 11L)).thenReturn(response("DRAFT"));

    CommonResult<QuoteEffectiveBomResponse> result =
        controller.getEffectiveBom("OA-1", 11L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().state()).isEqualTo("DRAFT");
    verify(service).getEffectiveBom("OA-1", 11L);
  }

  @Test
  void rebuildReturnsReadableInvalidScope() {
    when(service.rebuildPreview("OA-1", 11L))
        .thenThrow(
            new QuoteEffectiveBomQueryException(
                "EFFECTIVE_BOM_SCOPE_INVALID", "OA与产品行不匹配"));

    CommonResult<QuoteEffectiveBomResponse> result =
        controller.rebuildPreview("OA-1", 11L);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(400);
    assertThat(result.getMsg()).contains("EFFECTIVE_BOM_SCOPE_INVALID").contains("不匹配");
  }

  @Test
  void invalidOaScopeUsesStableBusinessCode() {
    when(service.getEffectiveBom("OA-1", 11L))
        .thenThrow(
            new QuoteEffectiveBomQueryException(
                "EFFECTIVE_BOM_SCOPE_INVALID", "OA与产品行不匹配"));

    CommonResult<QuoteEffectiveBomResponse> result =
        controller.getEffectiveBom("OA-1", 11L);

    assertThat(result.getCode()).isEqualTo(400);
    assertThat(result.getMsg()).startsWith("EFFECTIVE_BOM_SCOPE_INVALID:");
  }

  @Test
  void previewsOneAlternativeWithoutSavingIt() {
    QuoteEffectiveBomAlternativePreviewRequest request =
        new QuoteEffectiveBomAlternativePreviewRequest("2026-08", "GROUP-1", "T");
    when(service.previewAlternative("OA-1", 11L, "2026-08", "GROUP-1", "T"))
        .thenReturn(response("DRAFT"));

    CommonResult<QuoteEffectiveBomResponse> result =
        controller.previewAlternative("OA-1", 11L, request);

    assertThat(result.isSuccess()).isTrue();
    verify(service).previewAlternative("OA-1", 11L, "2026-08", "GROUP-1", "T");
  }

  private static QuoteEffectiveBomResponse response(String state) {
    return new QuoteEffectiveBomResponse(
        state,
        "OA-1",
        11L,
        "2026-08",
        "P",
        "CUSTOMER-A",
        "OA_HEADER_CUSTOMER",
        "BOX",
        "210",
        "COMMERCIAL",
        1L,
        "RAW-1",
        null,
        null,
        11L,
        List.of(),
        List.of(),
        new QuoteEffectiveBomExclusionSummaryResponse(true, 0, Map.of()),
        List.of(),
        List.of());
  }
}
