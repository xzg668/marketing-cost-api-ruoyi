package com.sanhua.marketingcost.service.effectivebom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPrunerImpl;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeCandidate;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIdentity;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EffectiveBomTestSupport {

  private EffectiveBomTestSupport() {}

  static QuoteEffectiveBomBuilder builder() {
    return new QuoteEffectiveBomBuilderImpl(
        new BomAlternativeBranchPrunerImpl(),
        new EffectiveBomPolicyActionResolver(new ObjectMapper()));
  }

  static EffectiveBomBuildRequest request(
      List<BomRawHierarchy> nodes,
      EffectiveBomShapeDecision... decisions) {
    return request(nodes, List.of(), Map.of(), 128, decisions);
  }

  static EffectiveBomBuildRequest request(
      List<BomRawHierarchy> nodes,
      List<BomAlternativeGroup> groups,
      Map<String, String> selections,
      int maxDepth,
      EffectiveBomShapeDecision... decisions) {
    Map<String, EffectiveBomShapeDecision> byMaterial =
        new LinkedHashMap<>();
    for (EffectiveBomShapeDecision decision : decisions) {
      byMaterial.put(decision.materialCode(), decision);
    }
    return new EffectiveBomBuildRequest(
        nodes, groups, selections, byMaterial, maxDepth);
  }

  static EffectiveBomShapeDecision shape(
      String materialCode, QuoteMaterialShape shape) {
    return EffectiveBomShapeDecision.u9(
        materialCode, shape.getLabel(), shape);
  }

  static EffectiveBomShapeDecision fixed(
      String materialCode,
      String sourceShape,
      QuoteMaterialShape effectiveShape) {
    return EffectiveBomShapeDecision.fixed(
        materialCode,
        sourceShape,
        effectiveShape,
        91L,
        "shape-policy-fingerprint");
  }

  static BomRawHierarchy node(
      long id,
      String materialCode,
      String parentCode,
      int level,
      String path,
      String qtyPerParent,
      String sourceShape) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setPriceOrgCode("210");
    row.setTopProductCode("P");
    row.setParentCode(parentCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName("名称-" + materialCode);
    row.setMaterialSpec("规格-" + materialCode);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq((int) id);
    row.setSourceLineKey("SOURCE-LINE-" + id);
    row.setQtyPerParent(new BigDecimal(qtyPerParent));
    row.setQtyPerTop(new BigDecimal("999"));
    row.setShapeAttr(sourceShape);
    row.setSourceType("U9");
    row.setSourceImportBatchId("IMPORT-1");
    row.setBuildBatchId("RAW-BUILD-1");
    row.setChildType("NORMAL");
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    return row;
  }

  static BomRawHierarchy alternativeNode(
      long id,
      String materialCode,
      int level,
      String path,
      String childType,
      String groupKey) {
    BomRawHierarchy row =
        node(
            id,
            materialCode,
            level == 1 ? "P" : pathParentMaterial(path),
            level,
            path,
            "1",
            level == 1 ? "制造件" : "采购件");
    row.setChildType(childType);
    row.setAlternativeGroupKey(groupKey);
    return row;
  }

  static BomAlternativeGroup alternativeGroup(
      String groupKey,
      BomRawHierarchy standard,
      BomRawHierarchy alternative) {
    return new BomAlternativeGroup(
        new BomAlternativeGroupIdentity(
            "210",
            "P",
            "PARENT-P",
            "P",
            "主制造",
            "V1",
            LocalDate.of(2026, 1, 1),
            null,
            10,
            "010"),
        groupKey,
        List.of(
            candidate(standard, BomChildType.STANDARD),
            candidate(alternative, BomChildType.ALTERNATIVE)));
  }

  static List<String> materialCodes(EffectiveBomBuildResult result) {
    return result.nodes().stream()
        .map(EffectiveBomNodeDraft::materialCode)
        .toList();
  }

  private static BomAlternativeCandidate candidate(
      BomRawHierarchy row, BomChildType childType) {
    return new BomAlternativeCandidate(
        row.getId(),
        row.getMaterialCode(),
        row.getMaterialName(),
        row.getMaterialSpec(),
        childType,
        row.getQtyPerParent(),
        row.getPath(),
        row.getSourceImportBatchId(),
        row.getBuildBatchId());
  }

  private static String pathParentMaterial(String path) {
    String normalized = path.endsWith("/")
        ? path.substring(0, path.length() - 1)
        : path;
    int last = normalized.lastIndexOf('/');
    if (last <= 0) {
      return "P";
    }
    String parentPath = normalized.substring(0, last);
    int parentSlash = parentPath.lastIndexOf('/');
    return parentPath.substring(parentSlash + 1);
  }
}
