package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-06 同月活动任务锁键")
class CollaborationActiveLockKeyFactoryTest {

  @Test
  @DisplayName("相同业务维度经空格和大小写归一后得到稳定锁键")
  void createsStableNormalizedKey() {
    String first = CollaborationActiveLockKeyFactory.create(
        "2026-08", " ab-001 ", null,
        new CollaborationScope(" commercial ", " 210 "), PrimaryScope.FULL_BOM);
    String replay = CollaborationActiveLockKeyFactory.create(
        "2026-08", "AB-001", null,
        new CollaborationScope("COMMERCIAL", "210"), PrimaryScope.FULL_BOM);

    assertThat(first).isEqualTo(replay).startsWith("QCBP-ACTIVE-V1:");
    assertThat(first).hasSize(79);
  }

  @Test
  @DisplayName("月份、组织、产品、临时产品键和主要范围任一变化均不会误锁")
  void isolatesEveryBusinessDimension() {
    CollaborationScope current = new CollaborationScope("COMMERCIAL", "210");
    String baseline = CollaborationActiveLockKeyFactory.create(
        "2026-08", "AB-001", null, current, PrimaryScope.FULL_BOM);

    assertThat(baseline).isNotEqualTo(CollaborationActiveLockKeyFactory.create(
        "2026-09", "AB-001", null, current, PrimaryScope.FULL_BOM));
    assertThat(baseline).isNotEqualTo(CollaborationActiveLockKeyFactory.create(
        "2026-08", "AB-001", null,
        new CollaborationScope("COMMERCIAL", "220"), PrimaryScope.FULL_BOM));
    assertThat(baseline).isNotEqualTo(CollaborationActiveLockKeyFactory.create(
        "2026-08", "AB-002", null, current, PrimaryScope.FULL_BOM));
    assertThat(baseline).isNotEqualTo(CollaborationActiveLockKeyFactory.create(
        "2026-08", "AB-001", null, current, PrimaryScope.PRICE_ONLY));
    assertThat(baseline).isNotEqualTo(CollaborationActiveLockKeyFactory.create(
        "2026-08", null, "NEW-001", current, PrimaryScope.FULL_BOM));
  }

  @Test
  @DisplayName("正式料号为空时使用稳定临时产品键，两者都空则明确拒绝")
  void supportsTemporaryProductKey() {
    String key = CollaborationActiveLockKeyFactory.create(
        "2026-08", null, " new-product-001 ",
        new CollaborationScope("COMMERCIAL", "210"), PrimaryScope.BARE_PACKAGE);

    assertThat(key).startsWith("QCBP-ACTIVE-V1:");
    assertThatThrownBy(() -> CollaborationActiveLockKeyFactory.create(
        "2026-08", null, " ",
        new CollaborationScope("COMMERCIAL", "210"), PrimaryScope.BARE_PACKAGE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("产品料号或临时产品键");
  }
}
