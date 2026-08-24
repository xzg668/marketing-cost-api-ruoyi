package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.hasher;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.crossOrganizationVariant;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EffectiveBomVariantHasherTest {

  @Test
  void crossOrganizationNodeIsPartOfVariantHash() {
    EffectiveBomVariantInput crossOrganization = crossOrganizationVariant();
    EffectiveBomVariantInput allPlate =
        new EffectiveBomVariantInput(
            crossOrganization.costPeriodMonth(),
            crossOrganization.sourceBomBatchId(),
            crossOrganization.priceOrgCode(),
            crossOrganization.topProductCode(),
            crossOrganization.packageMethod(),
            crossOrganization.selectedMaterialCodeByGroupKey(),
            new EffectiveBomBuildResult(
                crossOrganization.buildResult().nodes().stream()
                    .map(node -> EffectiveBomPersistenceTestSupport.withPriceOrg(node, "220"))
                    .toList(),
                crossOrganization.buildResult().exclusions(),
                crossOrganization.buildResult().blockIssues(),
                crossOrganization.buildResult().warnings()));

    assertThat(hasher().hash(crossOrganization)).isNotEqualTo(hasher().hash(allPlate));
  }

  @Test
  void collectionAndMapOrderDoNotChangeHash() {
    assertThat(hasher().hash(variant()))
        .isEqualTo(
            hasher()
                .hash(
                    variant(
                        "STANDARD-MATERIAL",
                        "BOX",
                        new BigDecimal("2.5000"),
                        QuoteMaterialShape.OUTSOURCE,
                        "POLICY-FP-1",
                        "SUP-EXT",
                        new BigDecimal("0.60"),
                        true)));
  }

  @Test
  void everyStructuralChoiceAndEvidenceChangeProducesDifferentHash() {
    String baseline = hasher().hash(variant());

    assertThat(
            List.of(
                variant(
                    "ALT-MATERIAL",
                    "BOX",
                    new BigDecimal("2.500"),
                    QuoteMaterialShape.OUTSOURCE,
                    "POLICY-FP-1",
                    "SUP-EXT",
                    new BigDecimal("0.6000"),
                    false),
                variant(
                    "STANDARD-MATERIAL",
                    "PALLET",
                    new BigDecimal("2.500"),
                    QuoteMaterialShape.OUTSOURCE,
                    "POLICY-FP-1",
                    "SUP-EXT",
                    new BigDecimal("0.6000"),
                    false),
                variant(
                    "STANDARD-MATERIAL",
                    "BOX",
                    new BigDecimal("2.501"),
                    QuoteMaterialShape.OUTSOURCE,
                    "POLICY-FP-1",
                    "SUP-EXT",
                    new BigDecimal("0.6000"),
                    false),
                variant(
                    "STANDARD-MATERIAL",
                    "BOX",
                    new BigDecimal("2.500"),
                    QuoteMaterialShape.MANUFACTURE,
                    "POLICY-FP-1",
                    "SUP-EXT",
                    new BigDecimal("0.6000"),
                    false),
                variant(
                    "STANDARD-MATERIAL",
                    "BOX",
                    new BigDecimal("2.500"),
                    QuoteMaterialShape.OUTSOURCE,
                    "POLICY-FP-2",
                    "SUP-EXT",
                    new BigDecimal("0.6000"),
                    false),
                variant(
                    "STANDARD-MATERIAL",
                    "BOX",
                    new BigDecimal("2.500"),
                    QuoteMaterialShape.OUTSOURCE,
                    "POLICY-FP-1",
                    "SUP-OTHER",
                    new BigDecimal("0.6000"),
                    false),
                variant(
                    "STANDARD-MATERIAL",
                    "BOX",
                    new BigDecimal("2.500"),
                    QuoteMaterialShape.OUTSOURCE,
                    "POLICY-FP-1",
                    "SUP-EXT",
                    new BigDecimal("0.6100"),
                    false)))
        .allSatisfy(input -> assertThat(hasher().hash(input)).isNotEqualTo(baseline));
  }

  @Test
  void blockedResultCannotBeHashedForConfirmation() {
    EffectiveBomVariantInput valid = variant();
    EffectiveBomVariantInput blocked =
        new EffectiveBomVariantInput(
            valid.costPeriodMonth(),
            valid.sourceBomBatchId(),
            valid.priceOrgCode(),
            valid.topProductCode(),
            valid.packageMethod(),
            valid.selectedMaterialCodeByGroupKey(),
            new EffectiveBomBuildResult(
                valid.buildResult().nodes(),
                valid.buildResult().exclusions(),
                List.of(
                    new EffectiveBomBlockIssue(
                        "BOM_GAP", "M", "/P/M/", "缺BOM")),
                List.of()));

    assertThatThrownBy(() -> hasher().hash(blocked))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("阻断");
  }

  @Test
  void malformedMonthAndAmbiguousSelectionKeysAreRejected() {
    EffectiveBomVariantInput valid = variant();
    EffectiveBomVariantInput badMonth =
        new EffectiveBomVariantInput(
            "2026-13",
            valid.sourceBomBatchId(),
            valid.priceOrgCode(),
            valid.topProductCode(),
            valid.packageMethod(),
            valid.selectedMaterialCodeByGroupKey(),
            valid.buildResult());
    Map<String, String> ambiguousSelections = new LinkedHashMap<>();
    ambiguousSelections.put("GROUP", "S");
    ambiguousSelections.put(" GROUP ", "T");
    EffectiveBomVariantInput ambiguous =
        new EffectiveBomVariantInput(
            valid.costPeriodMonth(),
            valid.sourceBomBatchId(),
            valid.priceOrgCode(),
            valid.topProductCode(),
            valid.packageMethod(),
            ambiguousSelections,
            valid.buildResult());

    assertThatThrownBy(() -> hasher().hash(badMonth))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("yyyy-MM");
    assertThatThrownBy(() -> hasher().hash(ambiguous))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");
  }

  @Test
  void contextSelectionAndExclusionOnlyChangesAlsoChangeHash() {
    EffectiveBomVariantInput valid = variant();
    Map<String, String> changedSelections =
        new LinkedHashMap<>(valid.selectedMaterialCodeByGroupKey());
    changedSelections.put("ALT-GROUP-2", "OTHER-MATERIAL-2");
    List<EffectiveBomExclusion> changedExclusions =
        new java.util.ArrayList<>(valid.buildResult().exclusions());
    EffectiveBomExclusion old = changedExclusions.getFirst();
    changedExclusions.set(
        0,
        new EffectiveBomExclusion(
            old.reasonCode(),
            old.triggerMaterialCode(),
            old.triggerSourcePath(),
            old.excludedRootMaterialCode(),
            old.excludedRootSourcePath(),
            old.excludedNodeCount() + 1,
            old.message()));
    EffectiveBomBuildResult changedExclusionResult =
        new EffectiveBomBuildResult(
            valid.buildResult().nodes(),
            changedExclusions,
            List.of(),
            List.of());
    String baseline = hasher().hash(valid);

    assertThat(
            List.of(
                new EffectiveBomVariantInput(
                    "2026-09",
                    valid.sourceBomBatchId(),
                    valid.priceOrgCode(),
                    valid.topProductCode(),
                    valid.packageMethod(),
                    valid.selectedMaterialCodeByGroupKey(),
                    valid.buildResult()),
                new EffectiveBomVariantInput(
                    valid.costPeriodMonth(),
                    "RAW-BATCH-2",
                    valid.priceOrgCode(),
                    valid.topProductCode(),
                    valid.packageMethod(),
                    valid.selectedMaterialCodeByGroupKey(),
                    valid.buildResult()),
                new EffectiveBomVariantInput(
                    valid.costPeriodMonth(),
                    valid.sourceBomBatchId(),
                    "220",
                    valid.topProductCode(),
                    valid.packageMethod(),
                    valid.selectedMaterialCodeByGroupKey(),
                    valid.buildResult()),
                new EffectiveBomVariantInput(
                    valid.costPeriodMonth(),
                    valid.sourceBomBatchId(),
                    valid.priceOrgCode(),
                    valid.topProductCode(),
                    valid.packageMethod(),
                    changedSelections,
                    valid.buildResult()),
                new EffectiveBomVariantInput(
                    valid.costPeriodMonth(),
                    valid.sourceBomBatchId(),
                    valid.priceOrgCode(),
                    valid.topProductCode(),
                    valid.packageMethod(),
                    valid.selectedMaterialCodeByGroupKey(),
                    changedExclusionResult)))
        .allSatisfy(input -> assertThat(hasher().hash(input)).isNotEqualTo(baseline));
  }
}
