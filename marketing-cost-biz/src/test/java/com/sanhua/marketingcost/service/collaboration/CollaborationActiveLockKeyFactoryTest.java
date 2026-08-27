package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-06 跨报价跨月份活动任务锁键")
class CollaborationActiveLockKeyFactoryTest {

  @Test
  @DisplayName("相同业务维度经空格和大小写归一后得到稳定锁键")
  void createsStableNormalizedKey() {
    String first = CollaborationActiveLockKeyFactory.create(
        " ab-001 ", "MODEL-A", null,
        new CollaborationScope(" commercial ", " 210 "));
    String replay = CollaborationActiveLockKeyFactory.create(
        "AB-001", "MODEL-B", null,
        new CollaborationScope("COMMERCIAL", "210"));

    assertThat(first).isEqualTo(replay).startsWith("QCBP-ACTIVE-V3:");
    assertThat(first).hasSize(79);
  }

  @Test
  @DisplayName("有料号优先按料号锁；不同组织或不同料号相互隔离")
  void isolatesEveryBusinessDimension() {
    CollaborationScope current = new CollaborationScope("COMMERCIAL", "210");
    String baseline = CollaborationActiveLockKeyFactory.create(
        "AB-001", "MODEL-A", null, current);

    assertThat(baseline).isNotEqualTo(CollaborationActiveLockKeyFactory.create(
        "AB-001", "MODEL-A", null,
        new CollaborationScope("COMMERCIAL", "220")));
    assertThat(baseline).isNotEqualTo(CollaborationActiveLockKeyFactory.create(
        "AB-002", "MODEL-A", null, current));
    assertThat(baseline).isEqualTo(CollaborationActiveLockKeyFactory.create(
        "AB-001", "MODEL-B", "TEMP-2", current));
    assertThat(baseline).isNotEqualTo(CollaborationActiveLockKeyFactory.create(
        null, "AB-001", "NEW-001", current));
  }

  @Test
  @DisplayName("无料号时同型号跨报价共用锁；型号也为空时才使用稳定临时键")
  void supportsModelAndTemporaryProductKey() {
    CollaborationScope scope = new CollaborationScope("COMMERCIAL", "210");
    String byModel = CollaborationActiveLockKeyFactory.create(
        null, " model-new-001 ", "OA_FORM_ITEM:1", scope);
    String sameModelFromAnotherQuote = CollaborationActiveLockKeyFactory.create(
        null, "MODEL-NEW-001", "OA_FORM_ITEM:2", scope);
    String byTemporary = CollaborationActiveLockKeyFactory.create(
        null, null, " new-product-001 ", scope);

    assertThat(byModel).isEqualTo(sameModelFromAnotherQuote);
    assertThat(byTemporary).startsWith("QCBP-ACTIVE-V3:");
    assertThatThrownBy(() -> CollaborationActiveLockKeyFactory.create(
        null, " ", " ", scope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("产品料号、型号或临时产品键");
  }
}
