package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.ingest.QuoteCostResultHistoryResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteMonthlyCostResultDetailResponse;
import com.sanhua.marketingcost.service.ingest.QuoteCostResultHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteCostResultHistoryControllerTest {
  private QuoteCostResultHistoryService service;
  private QuoteCostResultHistoryController controller;

  @BeforeEach
  void setUp() {
    service = mock(QuoteCostResultHistoryService.class);
    controller = new QuoteCostResultHistoryController(service);
  }

  @Test
  void returnsHistoryWithoutChangingCurrentCostingState() {
    QuoteCostResultHistoryResponse response = new QuoteCostResultHistoryResponse();
    response.setOaNo("OA-1");
    when(service.listHistory("OA-1", 9L)).thenReturn(response);

    var result = controller.history("OA-1", 9L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getOaNo()).isEqualTo("OA-1");
  }

  @Test
  void returnsMonthlyDetailWithinQuoteItemScope() {
    QuoteMonthlyCostResultDetailResponse response =
        new QuoteMonthlyCostResultDetailResponse();
    when(service.getMonthlyResult("OA-1", 9L, 6L)).thenReturn(response);

    var result = controller.monthlyResult("OA-1", 9L, 6L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(response);
  }
}
