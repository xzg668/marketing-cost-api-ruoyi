package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.CompensationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CollaborationOperationsServiceTest {
  @Test
  void compensationAlwaysRequiresIdempotencyKeyAndReason() {
    CollaborationOperationsService service = new CollaborationOperationsService(
        mock(JdbcTemplate.class), mock(CollaborationCurrentActorProvider.class));

    assertThatThrownBy(() -> service.invalidateApprovedResult(1L,
        new CompensationRequest("", "原因"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.invalidateApprovedResult(1L,
        new CompensationRequest("req-2", ""))).isInstanceOf(IllegalArgumentException.class);
  }
}
