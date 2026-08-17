package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sanhua.marketingcost.config.OaCollaborationProperties;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.CompensationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CollaborationOperationsServiceTest {
  @Test
  void oaDisabledModeCannotReleaseHoldOrPretendItWasSent() {
    OaCollaborationProperties properties = new OaCollaborationProperties();
    CollaborationOperationsService service = new CollaborationOperationsService(
        mock(JdbcTemplate.class), properties, mock(CollaborationCurrentActorProvider.class));

    assertThatThrownBy(() -> service.releaseOutbox(1L,
        new CompensationRequest("req-1", "OA尚未启用，仅验证门禁")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("必须保持HOLD");
  }

  @Test
  void compensationAlwaysRequiresIdempotencyKeyAndReason() {
    CollaborationOperationsService service = new CollaborationOperationsService(
        mock(JdbcTemplate.class), new OaCollaborationProperties(),
        mock(CollaborationCurrentActorProvider.class));

    assertThatThrownBy(() -> service.invalidateApprovedResult(1L,
        new CompensationRequest("", "原因"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.invalidateApprovedResult(1L,
        new CompensationRequest("req-2", ""))).isInstanceOf(IllegalArgumentException.class);
  }
}
