package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-05 报价BOM选择作用域隔离")
class QuoteBomAlternativeSelectionIsolationTest {

  @Test
  @DisplayName("不同OA、产品行、月份和业务单元各自拥有独立当前选择")
  void isolatesAllQuoteScopeDimensions() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    BomAlternativeGroup group = support.group();
    QuoteBomAlternativeSelectionScope base = support.scope();
    QuoteBomAlternativeSelectionScope otherOa =
        support.scope("OA-2", 10L, "2026-07", "COMMERCIAL");
    QuoteBomAlternativeSelectionScope otherItem =
        support.scope("OA-1", 11L, "2026-07", "COMMERCIAL");
    QuoteBomAlternativeSelectionScope otherMonth =
        support.scope("OA-1", 10L, "2026-08", "COMMERCIAL");
    QuoteBomAlternativeSelectionScope otherBusinessUnit =
        support.scope("OA-1", 10L, "2026-07", "HOUSEHOLD");

    support.service.ensureDefault(base, group);
    support.service.ensureDefault(otherOa, group);
    support.service.ensureDefault(otherItem, group);
    support.service.ensureDefault(otherMonth, group);
    support.service.ensureDefault(otherBusinessUnit, group);

    assertThat(support.repository.rows).hasSize(5);
    assertThat(support.service.findCurrent(
        otherBusinessUnit, QuoteBomAlternativeSelectionTestSupport.GROUP_KEY))
        .isNotNull();
    assertThat(support.service.history(
        base, QuoteBomAlternativeSelectionTestSupport.GROUP_KEY))
        .hasSize(1);
  }

  @Test
  @DisplayName("同一作用域的不同替代组互不覆盖")
  void isolatesAlternativeGroupsInsideSameQuote() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();

    support.service.ensureDefault(support.scope(), support.group());
    support.service.ensureDefault(
        support.scope(),
        support.group(
            QuoteBomAlternativeSelectionTestSupport.OTHER_GROUP_KEY,
            "STD-2",
            java.util.List.of("ALT-2"),
            "BUILD-1"));

    assertThat(support.repository.rows).hasSize(2);
    assertThat(support.service.findCurrent(
        support.scope(), QuoteBomAlternativeSelectionTestSupport.GROUP_KEY))
        .isNotNull();
    assertThat(support.service.findCurrent(
        support.scope(), QuoteBomAlternativeSelectionTestSupport.OTHER_GROUP_KEY))
        .isNotNull();
  }
}
