package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-05 报价BOM选择版本、幂等和失效")
class QuoteBomAlternativeSelectionVersionTest {

  @Test
  @DisplayName("自动标准、人工替代、恢复标准形成完整版本1到3历史")
  void preservesCompleteThreeVersionHistory() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    support.service.ensureDefault(support.scope(), support.group());
    support.service.save(support.command("ALT", 1), support.group());
    QuoteBomAlternativeSelectionCommand restore =
        new QuoteBomAlternativeSelectionCommand(
            support.scope(),
            QuoteBomAlternativeSelectionTestSupport.GROUP_KEY,
            "STD",
            2,
            "BUILD-1",
            "quote-user",
            "恢复标准");

    QuoteBomAlternativeSelectionResult result =
        support.service.save(restore, support.group());
    List<QuoteBomAlternativeSelectionResult> history =
        support.service.history(
            support.scope(), QuoteBomAlternativeSelectionTestSupport.GROUP_KEY);

    assertThat(result.selectionVersion()).isEqualTo(3);
    assertThat(result.selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD);
    assertThat(history)
        .extracting(
            QuoteBomAlternativeSelectionResult::selectionVersion,
            QuoteBomAlternativeSelectionResult::selectedMaterialCode,
            QuoteBomAlternativeSelectionResult::selectionStatus)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                1, "STD", QuoteBomAlternativeSelection.STATUS_SUPERSEDED),
            org.assertj.core.groups.Tuple.tuple(
                2, "ALT", QuoteBomAlternativeSelection.STATUS_SUPERSEDED),
            org.assertj.core.groups.Tuple.tuple(
                3, "STD", QuoteBomAlternativeSelection.STATUS_ACTIVE));
  }

  @Test
  @DisplayName("重复保存当前料号幂等返回，不产生新版本")
  void repeatedSelectionIsIdempotent() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    support.service.ensureDefault(support.scope(), support.group());
    support.service.save(support.command("ALT", 1), support.group());

    QuoteBomAlternativeSelectionResult repeated =
        support.service.save(support.command("ALT", 1), support.group());

    assertThat(repeated.selectionVersion()).isEqualTo(2);
    assertThat(repeated.idempotent()).isTrue();
    assertThat(support.repository.rows).hasSize(2);
  }

  @Test
  @DisplayName("人工所选候选消失后标记STALE且不会静默改回标准件")
  void marksMissingManualCandidateStaleWithoutFallback() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    support.service.ensureDefault(support.scope(), support.group());
    support.service.save(support.command("ALT", 1), support.group());
    BomAlternativeGroup changed =
        support.group(
            QuoteBomAlternativeSelectionTestSupport.GROUP_KEY,
            "STD",
            List.of("ALT-NEW"),
            "BUILD-2");

    QuoteBomAlternativeSelectionResult stale =
        support.service.reconcile(support.scope(), changed);
    QuoteBomAlternativeSelectionResult repeated =
        support.service.ensureDefault(support.scope(), changed);

    assertThat(stale.selectionStatus())
        .isEqualTo(QuoteBomAlternativeSelectionServiceImpl.STATUS_PREVIEW);
    assertThat(stale.selectedMaterialCode()).isEqualTo("STD");
    assertThat(stale.selectionVersion()).isEqualTo(2);
    assertThat(stale.reviewRequired()).isTrue();
    assertThat(repeated.reviewRequired()).isTrue();
    assertThat(repeated.persisted()).isFalse();
    assertThat(support.service.findCurrent(
        support.scope(), QuoteBomAlternativeSelectionTestSupport.GROUP_KEY))
        .isNull();
    assertThat(support.repository.rows).hasSize(2);
    assertThat(support.repository.rows.getLast().getSelectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_STALE);
    assertThat(support.repository.rows.getLast().getSelectedMaterialCode())
        .isEqualTo("ALT");

    QuoteBomAlternativeSelectionCommand confirmNewAlternative =
        new QuoteBomAlternativeSelectionCommand(
            support.scope(),
            QuoteBomAlternativeSelectionTestSupport.GROUP_KEY,
            "ALT-NEW",
            stale.selectionVersion(),
            "BUILD-2",
            "quote-user",
            "确认新候选");
    QuoteBomAlternativeSelectionResult confirmed =
        support.service.save(confirmNewAlternative, changed);
    assertThat(confirmed.selectionVersion()).isEqualTo(3);
    assertThat(confirmed.selectedMaterialCode()).isEqualTo("ALT-NEW");
  }

  @Test
  @DisplayName("相同组键和候选重新构建时保留版本，仅刷新来源批次")
  void retainsSelectionForSameGroupAndCandidateAfterRebuild() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    support.service.ensureDefault(support.scope(), support.group());
    support.service.save(support.command("ALT", 1), support.group());
    BomAlternativeGroup rebuilt =
        support.group(
            QuoteBomAlternativeSelectionTestSupport.GROUP_KEY,
            "STD",
            List.of("ALT"),
            "BUILD-2");

    QuoteBomAlternativeSelectionResult result =
        support.service.reconcile(support.scope(), rebuilt);

    assertThat(result.selectionVersion()).isEqualTo(2);
    assertThat(result.selectedMaterialCode()).isEqualTo("ALT");
    assertThat(result.sourceBuildBatchId()).isEqualTo("BUILD-2");
    assertThat(support.repository.rows).hasSize(2);
  }

  @Test
  @DisplayName("新月份使用独立作用域并从自动标准版本1开始")
  void newMonthStartsAtVersionOne() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    support.service.ensureDefault(support.scope(), support.group());
    QuoteBomAlternativeSelectionScope august =
        support.scope("OA-1", 10L, "2026-08", "COMMERCIAL");

    QuoteBomAlternativeSelectionResult result =
        support.service.ensureDefault(august, support.group());

    assertThat(result.selectionVersion()).isEqualTo(1);
    assertThat(support.repository.rows).hasSize(2);
  }

  @Test
  @DisplayName("BOM版本变化导致组键变化时旧选择STALE，新标准只预览待复核")
  void newGroupKeyCreatesReviewPreviewWithoutSilentActivation() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    support.service.ensureDefault(support.scope(), support.group());
    support.service.save(support.command("ALT", 1), support.group());
    BomAlternativeGroup source =
        support.group(
            QuoteBomAlternativeSelectionTestSupport.OTHER_GROUP_KEY,
            "STD-V2",
            List.of("ALT-V2"),
            "BUILD-2");
    BomAlternativeGroup changedVersion =
        new BomAlternativeGroup(
            new BomAlternativeGroupIdentity(
                "210",
                "TOP",
                "parent-fingerprint",
                "PARENT",
                "主制造",
                "F007",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(9999, 12, 31),
                10,
                "010"),
            source.alternativeGroupKey(),
            source.candidates());

    QuoteBomAlternativeSelectionResult preview =
        support.service
            .synchronize(support.scope(), List.of(changedVersion))
            .getFirst();

    assertThat(preview.selectionStatus())
        .isEqualTo(QuoteBomAlternativeSelectionServiceImpl.STATUS_PREVIEW);
    assertThat(preview.selectedMaterialCode()).isEqualTo("STD-V2");
    assertThat(preview.reviewRequired()).isTrue();
    assertThat(preview.persisted()).isFalse();
    assertThat(support.service.findCurrent(
        support.scope(), QuoteBomAlternativeSelectionTestSupport.GROUP_KEY))
        .isNull();
    assertThat(support.service.findCurrent(
        support.scope(), QuoteBomAlternativeSelectionTestSupport.OTHER_GROUP_KEY))
        .isNull();

    QuoteBomAlternativeSelectionCommand confirmPreview =
        new QuoteBomAlternativeSelectionCommand(
            support.scope(),
            QuoteBomAlternativeSelectionTestSupport.OTHER_GROUP_KEY,
            "STD-V2",
            0,
            "BUILD-2",
            "quote-user",
            "确认新版本标准件");
    QuoteBomAlternativeSelectionResult confirmed =
        support.service.save(confirmPreview, changedVersion);
    assertThat(confirmed.selectionVersion()).isEqualTo(1);
    assertThat(confirmed.selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD);
  }
}
