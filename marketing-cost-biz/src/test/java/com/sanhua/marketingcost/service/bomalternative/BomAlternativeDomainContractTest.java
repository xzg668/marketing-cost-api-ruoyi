package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-02 BOM替代组纯领域契约")
class BomAlternativeDomainContractTest {

  @Test
  @DisplayName("稳定身份只包含十个业务位置字段")
  void identityContainsOnlyStableBusinessPositionFields() {
    List<String> components = Arrays.stream(
            BomAlternativeGroupIdentity.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();

    assertThat(components).containsExactly(
        "priceOrgCode",
        "topProductCode",
        "parentPathFingerprint",
        "parentMaterialNo",
        "bomPurpose",
        "bomVersion",
        "effectiveFrom",
        "effectiveTo",
        "childSeq",
        "processSeq");
    assertThat(components).doesNotContain(
        "materialCode",
        "standardMaterialCode",
        "alternativeMaterialCode",
        "id",
        "sourceU9RowId",
        "importBatchId",
        "buildBatchId",
        "importedAt",
        "operator");
  }

  @Test
  @DisplayName("候选契约保留展示、用量、路径和来源追溯但不参与身份")
  void candidateCarriesDisplayAndTraceFields() {
    BomAlternativeCandidate candidate = new BomAlternativeCandidate(
        287987L,
        "201850659",
        "芯体部件",
        "YCQB02-021604",
        BomChildType.STANDARD,
        BigDecimal.ONE,
        "/1145900000302/101850644/201850659/",
        "u9_bom_2026-07-06",
        "h_20260706");

    assertThat(candidate.rawHierarchyNodeId()).isEqualTo(287987L);
    assertThat(candidate.materialCode()).isEqualTo("201850659");
    assertThat(candidate.childType()).isEqualTo(BomChildType.STANDARD);
    assertThat(candidate.qtyPerParent()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(candidate.sourceImportBatchId()).isEqualTo("u9_bom_2026-07-06");
    assertThat(candidate.sourceBuildBatchId()).isEqualTo("h_20260706");
  }

  @Test
  @DisplayName("替代组候选列表做不可变快照且不执行默认选择")
  void groupUsesImmutableCandidateSnapshotWithoutSelecting() {
    BomAlternativeGroupIdentity identity = new BomAlternativeGroupIdentity(
        "210", "TOP", "PARENT-FINGERPRINT", "PARENT", "主制造", "F006",
        LocalDate.parse("2026-05-21"), LocalDate.parse("9999-12-31"), 10, "010");
    BomAlternativeCandidate standard = new BomAlternativeCandidate(
        1L, "STANDARD", "标准件", null, BomChildType.STANDARD,
        BigDecimal.ONE, "/standard/", "batch", "build");
    BomAlternativeCandidate alternative = new BomAlternativeCandidate(
        2L, "ALTERNATIVE", "替代件", null, BomChildType.ALTERNATIVE,
        BigDecimal.ONE, "/alternative/", "batch", "build");
    List<BomAlternativeCandidate> mutable = new java.util.ArrayList<>(
        List.of(standard, alternative));

    BomAlternativeGroup group = new BomAlternativeGroup(
        identity, "group-key", mutable);
    mutable.clear();

    assertThat(group.candidates()).containsExactly(standard, alternative);
    assertThat(group.candidates()).isUnmodifiable();
    assertThat(Arrays.stream(BomAlternativeGroup.class.getRecordComponents())
        .map(RecordComponent::getName))
        .doesNotContain("selectedCandidate", "defaultCandidate");
  }
}
