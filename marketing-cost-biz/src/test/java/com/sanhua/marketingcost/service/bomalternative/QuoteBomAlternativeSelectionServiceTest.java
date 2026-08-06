package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-05 报价BOM标准/替代选择服务")
class QuoteBomAlternativeSelectionServiceTest {

  @Test
  @DisplayName("首次进入替代组自动保存标准件版本1并完整留痕")
  void createsAutoStandardVersionOne() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();

    QuoteBomAlternativeSelectionResult result =
        support.service.ensureDefault(support.scope(), support.group());

    assertThat(result.selectionVersion()).isEqualTo(1);
    assertThat(result.selectedMaterialCode()).isEqualTo("STD");
    assertThat(result.selectedChildType()).isEqualTo(BomChildType.STANDARD);
    assertThat(result.selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD);
    assertThat(result.selectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_ACTIVE);
    assertThat(result.persisted()).isTrue();
    assertThat(result.reviewRequired()).isFalse();
    assertThat(support.repository.rows).hasSize(1);
    assertThat(support.repository.rows.getFirst().getCurrentSlot())
        .isEqualTo(QuoteBomAlternativeSelection.CURRENT_SLOT);
  }

  @Test
  @DisplayName("报价员选择组内替代件生成版本2并替代旧当前版本")
  void selectsAlternativeAsVersionTwo() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    support.service.ensureDefault(support.scope(), support.group());

    QuoteBomAlternativeSelectionResult result =
        support.service.save(support.command("ALT", 1), support.group());

    assertThat(result.selectionVersion()).isEqualTo(2);
    assertThat(result.selectedMaterialCode()).isEqualTo("ALT");
    assertThat(result.selectedChildType()).isEqualTo(BomChildType.ALTERNATIVE);
    assertThat(result.selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    assertThat(support.repository.rows)
        .extracting(
            QuoteBomAlternativeSelection::getSelectionStatus,
            QuoteBomAlternativeSelection::getCurrentSlot)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                QuoteBomAlternativeSelection.STATUS_SUPERSEDED, null),
            org.assertj.core.groups.Tuple.tuple(
                QuoteBomAlternativeSelection.STATUS_ACTIVE,
                QuoteBomAlternativeSelection.CURRENT_SLOT));
  }

  @Test
  @DisplayName("不属于当前替代组的任意料号不能保存")
  void rejectsMaterialOutsideCurrentGroup() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    support.service.ensureDefault(support.scope(), support.group());

    assertThatThrownBy(
            () ->
                support.service.save(
                    support.command("NOT-IN-BOM", 1), support.group()))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting("code")
        .isEqualTo("ALT_CANDIDATE_INVALID");
    assertThat(support.repository.rows).hasSize(1);
  }

  @Test
  @DisplayName("替代组的产品或组织与报价作用域不一致时拒绝")
  void rejectsGroupOutsideQuoteScope() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    QuoteBomAlternativeSelectionScope wrongTopScope =
        new QuoteBomAlternativeSelectionScope(
            "OA-1", 10L, "OTHER-TOP", "2026-07", "210", "COMMERCIAL");

    assertThatThrownBy(
            () -> support.service.ensureDefault(wrongTopScope, support.group()))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting("code")
        .isEqualTo("ALT_GROUP_NOT_FOUND");
    assertThat(support.repository.rows).isEmpty();
  }
}
