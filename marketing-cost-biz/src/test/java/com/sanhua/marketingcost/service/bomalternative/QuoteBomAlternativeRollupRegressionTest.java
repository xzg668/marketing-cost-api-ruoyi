package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-13 标准/替代分支上卷等价回归")
class QuoteBomAlternativeRollupRegressionTest {

  private QuoteBomAlternativeRealDataTestSupport support;

  @BeforeEach
  void setUp() {
    support = new QuoteBomAlternativeRealDataTestSupport();
  }

  @Test
  @DisplayName("标准子树直接进入旧引擎与经新裁剪后逐字段完全一致")
  void standardBranchKeepsLegacySettlementOutput() {
    assertEquivalent(
        support.legacyDirectEngineSnapshot(
            QuoteBomAlternativeRealDataTestSupport.STANDARD),
        support.selectedEngineSnapshot(
            QuoteBomAlternativeRealDataTestSupport.STANDARD));
  }

  @Test
  @DisplayName("替代子树直接进入旧引擎与经新裁剪后逐字段完全一致")
  void alternativeBranchKeepsLegacySettlementOutput() {
    assertEquivalent(
        support.legacyDirectEngineSnapshot(
            QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE),
        support.selectedEngineSnapshot(
            QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE));
  }

  @Test
  @DisplayName("真实接管上卷仍是一条父件成本行和一条命中子件引用")
  void realDrawnCopperTubeKeepsParentAndChildTrace() {
    var snapshot =
        support.selectedEngineSnapshot(
            QuoteBomAlternativeRealDataTestSupport.STANDARD);

    assertThat(snapshot.rowFingerprints())
        .filteredOn(
            row ->
                row.contains("|721850051|接管|")
                    && row.contains("|SPECIAL_ROLLUP_PARENT|"))
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .contains("真实结构-721850051")
                    .contains("|1|1|"));
    assertThat(snapshot.subRefFingerprints())
        .filteredOn(
            ref ->
                ref.contains("|SPECIAL_ROLLUP_CHILD|")
                    && ref.contains("|301060256|拉制铜管|"))
        .singleElement()
        .satisfies(
            ref ->
                assertThat(ref)
                    .contains("0.00692983"));
    assertThat(snapshot.rowFingerprints())
        .noneMatch(
            row ->
                row.contains("|301060256|")
                    && row.contains("|DEFAULT_LEAF|"));
  }

  private static void assertEquivalent(
      QuoteBomAlternativeRealDataTestSupport.EngineSnapshot baseline,
      QuoteBomAlternativeRealDataTestSupport.EngineSnapshot candidate) {
    assertThat(baseline.inputNodeCount()).isEqualTo(56);
    assertThat(candidate.inputNodeCount()).isEqualTo(56);
    assertThat(candidate.rowFingerprints())
        .containsExactlyElementsOf(baseline.rowFingerprints());
    assertThat(candidate.subRefFingerprints())
        .containsExactlyElementsOf(baseline.subRefFingerprints());
    assertThat(candidate.sourceRefFingerprints())
        .containsExactlyElementsOf(baseline.sourceRefFingerprints());
    assertThat(candidate.warnings())
        .containsExactlyElementsOf(baseline.warnings());
  }
}
