package com.sanhua.marketingcost.service.effectivebom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class EffectiveBomPersistenceTestSupport {

  private EffectiveBomPersistenceTestSupport() {}

  static EffectiveBomVariantHasher hasher() {
    return new EffectiveBomVariantHasherImpl(new ObjectMapper());
  }

  static QuoteEffectiveBomPersistenceServiceImpl service(
      InMemoryRepository repository) {
    AtomicInteger sequence = new AtomicInteger();
    return service(
        repository,
        hasher(),
        () -> "BUILD-" + sequence.incrementAndGet());
  }

  static QuoteEffectiveBomPersistenceServiceImpl service(
      QuoteEffectiveBomRepository repository,
      EffectiveBomVariantHasher hasher,
      EffectiveBomBuildIdGenerator idGenerator) {
    return new QuoteEffectiveBomPersistenceServiceImpl(
        repository,
        hasher,
        idGenerator,
        Clock.fixed(Instant.parse("2026-08-04T01:00:00Z"), ZoneOffset.UTC));
  }

  static QuoteEffectiveBomPersistenceRequest request(
      long originSnapshotId, EffectiveBomVariantInput variantInput) {
    return new QuoteEffectiveBomPersistenceRequest(
        originSnapshotId,
        9527L,
        Map.of("ALT-GROUP-1", originSnapshotId + 1000),
        variantInput);
  }

  static EffectiveBomVariantInput variant() {
    return variant(
        "STANDARD-MATERIAL",
        "BOX",
        new BigDecimal("2.500"),
        QuoteMaterialShape.OUTSOURCE,
        "POLICY-FP-1",
        "SUP-EXT",
        new BigDecimal("0.6000"),
        false);
  }

  static EffectiveBomVariantInput variant(
      String selectedMaterialCode,
      String packageMethod,
      BigDecimal childQty,
      QuoteMaterialShape childShape,
      String policyFingerprint,
      String supplierCode,
      BigDecimal supplyRatio,
      boolean reverseOrder) {
    EffectiveBomNodeDraft root =
        new EffectiveBomNodeDraft(
            "ROOT",
            null,
            0,
            0,
            "/P/",
            "P",
            "产品P",
            "P-SPEC",
            "210",
            BigDecimal.ONE,
            BigDecimal.ONE,
            "制造件",
            QuoteMaterialShape.MANUFACTURE,
            MaterialQuoteShapeSource.U9,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "U9",
            "RAW-BATCH-1",
            1L,
            "/P/");
    EffectiveBomNodeDraft child =
        new EffectiveBomNodeDraft(
            "CHILD-1",
            "ROOT",
            1,
            10,
            "/P/" + selectedMaterialCode + "/",
            selectedMaterialCode,
            "候选料",
            "C-SPEC",
            "210",
            childQty,
            childQty,
            "制造件",
            childShape,
            MaterialQuoteShapeSource.SUPPLIER_RATIO,
            701L,
            policyFingerprint,
            801L,
            supplierCode,
            "供应商-" + supplierCode,
            supplyRatio,
            "ALT-GROUP-1",
            selectedMaterialCode.startsWith("ALT-") ? "ALTERNATIVE" : "STANDARD",
            selectedMaterialCode.startsWith("ALT-") ? "MANUAL" : "AUTO_DEFAULT",
            "U9",
            "RAW-BATCH-1",
            2L,
            "/P/" + selectedMaterialCode + "/");
    List<EffectiveBomNodeDraft> nodes =
        reverseOrder ? List.of(child, root) : List.of(root, child);
    List<EffectiveBomExclusion> exclusions =
        new ArrayList<>(
            List.of(
                new EffectiveBomExclusion(
                    "POLICY_DIRECT_CHILD_EXCLUSION",
                    selectedMaterialCode,
                    child.nodePath(),
                    "EXCLUDED-X",
                    child.nodePath() + "EXCLUDED-X/",
                    2,
                    "规则排除X"),
                new EffectiveBomExclusion(
                    "ALTERNATIVE_UNSELECTED",
                    "P",
                    "/P/",
                    "EXCLUDED-Y",
                    "/P/EXCLUDED-Y/",
                    1,
                    "未选分支Y")));
    if (reverseOrder) {
      java.util.Collections.reverse(exclusions);
    }
    Map<String, String> selections = new LinkedHashMap<>();
    if (reverseOrder) {
      selections.put("ALT-GROUP-2", "MATERIAL-2");
      selections.put("ALT-GROUP-1", selectedMaterialCode);
    } else {
      selections.put("ALT-GROUP-1", selectedMaterialCode);
      selections.put("ALT-GROUP-2", "MATERIAL-2");
    }
    return new EffectiveBomVariantInput(
        "2026-08",
        "RAW-BATCH-1",
        "210",
        "P",
        packageMethod,
        selections,
        new EffectiveBomBuildResult(nodes, exclusions, List.of(), List.of()));
  }

  static EffectiveBomNodeDraft withPriceOrg(
      EffectiveBomNodeDraft source, String priceOrgCode) {
    return new EffectiveBomNodeDraft(
        source.nodeKey(),
        source.parentNodeKey(),
        source.nodeLevel(),
        source.sortSeq(),
        source.nodePath(),
        source.materialCode(),
        source.materialName(),
        source.materialSpec(),
        priceOrgCode,
        source.qtyPerParent(),
        source.qtyPerTop(),
        source.sourceMaterialShape(),
        source.effectiveMaterialShape(),
        source.shapeResolutionSource(),
        source.shapePolicyId(),
        source.shapePolicyFingerprint(),
        source.selectedSupplierRatioId(),
        source.selectedSupplierCode(),
        source.selectedSupplierName(),
        source.selectedSupplyRatio(),
        source.alternativeGroupKey(),
        source.alternativeChildType(),
        source.alternativeSelectionSource(),
        source.sourceBomType(),
        source.sourceBomBatchId(),
        source.sourceHierarchyId(),
        source.sourceNodePath());
  }

  static EffectiveBomVariantInput crossOrganizationVariant() {
    EffectiveBomVariantInput source = variant();
    List<EffectiveBomNodeDraft> nodes = source.buildResult().nodes();
    return new EffectiveBomVariantInput(
        source.costPeriodMonth(),
        source.sourceBomBatchId(),
        "220",
        source.topProductCode(),
        source.packageMethod(),
        source.selectedMaterialCodeByGroupKey(),
        new EffectiveBomBuildResult(
            List.of(withPriceOrg(nodes.getFirst(), "220"), withPriceOrg(nodes.get(1), "210")),
            source.buildResult().exclusions(),
            source.buildResult().blockIssues(),
            source.buildResult().warnings()));
  }

  static final class InMemoryRepository implements QuoteEffectiveBomRepository {

    private final Map<String, List<QuoteEffectiveBomNode>> rowsByBatch =
        new LinkedHashMap<>();
    private int insertCalls;

    @Override
    public List<String> findBuildBatchIdsByVariantHash(String variantHash) {
      return rowsByBatch.entrySet().stream()
          .filter(entry -> !entry.getValue().isEmpty())
          .filter(
              entry ->
                  variantHash.equals(
                      entry.getValue().getFirst().getEffectiveVariantHash()))
          .map(Map.Entry::getKey)
          .toList();
    }

    @Override
    public List<QuoteEffectiveBomNode> findNodesByBuildBatchId(
        String buildBatchId) {
      return rowsByBatch.getOrDefault(buildBatchId, List.of());
    }

    @Override
    public boolean existsBuildBatchId(String buildBatchId) {
      return rowsByBatch.containsKey(buildBatchId);
    }

    @Override
    public void insertAll(List<QuoteEffectiveBomNode> nodes) {
      insertCalls++;
      rowsByBatch.put(nodes.getFirst().getBuildBatchId(), List.copyOf(nodes));
    }

    int insertCalls() {
      return insertCalls;
    }

    int buildCount() {
      return rowsByBatch.size();
    }

    List<QuoteEffectiveBomNode> nodes(String buildBatchId) {
      return rowsByBatch.get(buildBatchId);
    }
  }
}
