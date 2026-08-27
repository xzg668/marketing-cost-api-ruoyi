package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-03 协作命令幂等契约")
class CollaborationIdempotencyKeysTest {

  @Test
  @DisplayName("五类内部命令生成稳定且不串业务的幂等键")
  void buildsStableBusinessKeys() {
    assertThat(CollaborationIdempotencyKeys.start(91L, "2026-08", PrimaryScope.FULL_BOM))
        .isEqualTo("91:2026-08:FULL_BOM:START");
    assertThat(CollaborationIdempotencyKeys.technicalSubmit("QC-P-1", 3))
        .isEqualTo("QC-P-1:3:TECH_SUBMIT");
    assertThat(CollaborationIdempotencyKeys.reviewSubmit("QC-R-1", 7))
        .isEqualTo("QC-R-1:7:REVIEW_SUBMIT");
    assertThat(CollaborationIdempotencyKeys.publish("PUB-1", 12L))
        .isEqualTo("PUB-1:12");
    assertThat(CollaborationIdempotencyKeys.reprice(8L, "PUB-1"))
        .isEqualTo("8:PUB-1:REPRICE");
  }

  @Test
  @DisplayName("JSON字段顺序不同视为同一报文，刷新重发返回REPLAY")
  void treatsCanonicalPayloadAsReplay() {
    CollaborationIdempotency idempotency = new CollaborationIdempotency();
    String first = idempotency.payloadHash("{\"taskVersion\":3,\"items\":[1,2]}");
    String refreshed = idempotency.payloadHash("{\"items\":[1,2],\"taskVersion\":3}");

    assertThat(refreshed).isEqualTo(first);
    assertThat(idempotency.check(first, refreshed))
        .isEqualTo(CollaborationIdempotency.Decision.REPLAY);
  }

  @Test
  @DisplayName("同一幂等键换payload明确冲突且不允许覆盖原请求")
  void rejectsSameKeyWithDifferentPayload() {
    CollaborationIdempotency idempotency = new CollaborationIdempotency();
    String existing = idempotency.payloadHash("{\"decision\":\"PASS\"}");
    String changed = idempotency.payloadHash("{\"decision\":\"REJECT\"}");

    assertThatThrownBy(() -> idempotency.check(existing, changed))
        .isInstanceOfSatisfying(CollaborationDomainException.class, error ->
            assertThat(error.code()).isEqualTo(
                CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT));
  }

  @Test
  @DisplayName("空值、非法版本和非法JSON不能形成幂等凭据")
  void rejectsUnstableKeyInputs() {
    assertThatThrownBy(() -> CollaborationIdempotencyKeys.technicalSubmit(" ", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CollaborationIdempotencyKeys.reviewSubmit("QC-R-1", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollaborationIdempotency().payloadHash("not-json"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
