package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class BomAlternativePrunerTestSupport {

  static final String GROUP_MAIN = "group-main";
  static final String GROUP_PARALLEL = "group-parallel";
  static final String GROUP_NESTED_SELECTED = "group-nested-selected";
  static final String GROUP_NESTED_UNSELECTED = "group-nested-unselected";

  final BomAlternativeBranchPruner pruner =
      new BomAlternativeBranchPrunerImpl();

  List<BomRawHierarchy> baseTree() {
    return new ArrayList<>(
        List.of(
            row(1, "TOP", "TOP", 0, "/TOP/", null, null, "1", "1"),
            row(
                2,
                "PARENT",
                "TOP",
                1,
                "/TOP/PARENT/",
                null,
                null,
                "1",
                "1"),
            row(
                3,
                "STD",
                "PARENT",
                2,
                "/TOP/PARENT/STD/",
                GROUP_MAIN,
                "STANDARD",
                "2",
                "2"),
            row(
                4,
                "STD-CHILD",
                "STD",
                3,
                "/TOP/PARENT/STD/STD-CHILD/",
                null,
                null,
                "3",
                "6"),
            row(
                5,
                "STD-GRAND",
                "STD-CHILD",
                4,
                "/TOP/PARENT/STD/STD-CHILD/STD-GRAND/",
                null,
                null,
                "5",
                "30"),
            row(
                6,
                "ALT",
                "PARENT",
                2,
                "/TOP/PARENT/ALT/",
                GROUP_MAIN,
                "ALTERNATIVE",
                "4",
                "4"),
            row(
                7,
                "ALT-CHILD",
                "ALT",
                3,
                "/TOP/PARENT/ALT/ALT-CHILD/",
                null,
                null,
                "7",
                "28"),
            row(
                8,
                "ALT-GRAND",
                "ALT-CHILD",
                4,
                "/TOP/PARENT/ALT/ALT-CHILD/ALT-GRAND/",
                null,
                null,
                "11",
                "308"),
            row(
                9,
                "ORDINARY",
                "TOP",
                1,
                "/TOP/ORDINARY/",
                null,
                null,
                "1",
                "1")));
  }

  BomAlternativeGroup mainGroup(List<BomRawHierarchy> rows) {
    return group(
        GROUP_MAIN,
        "PARENT",
        find(rows, "/TOP/PARENT/STD/"),
        find(rows, "/TOP/PARENT/ALT/"));
  }

  BomAlternativeGroup group(
      String groupKey,
      String parentCode,
      BomRawHierarchy standard,
      BomRawHierarchy... alternatives) {
    List<BomAlternativeCandidate> candidates = new ArrayList<>();
    candidates.add(candidate(standard, BomChildType.STANDARD));
    for (BomRawHierarchy alternative : alternatives) {
      candidates.add(candidate(alternative, BomChildType.ALTERNATIVE));
    }
    return new BomAlternativeGroup(
        new BomAlternativeGroupIdentity(
            "210",
            "TOP",
            "fingerprint-" + parentCode,
            parentCode,
            "主制造",
            "F006",
            LocalDate.of(2026, 5, 21),
            LocalDate.of(9999, 12, 31),
            10,
            "010"),
        groupKey,
        candidates);
  }

  BomRawHierarchy row(
      long id,
      String materialCode,
      String parentCode,
      int level,
      String path,
      String groupKey,
      String childType,
      String qtyPerParent,
      String qtyPerTop) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setPriceOrgCode("210");
    row.setTopProductCode("TOP");
    row.setParentCode(parentCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName("名称-" + materialCode);
    row.setMaterialSpec("规格-" + materialCode);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq((int) id);
    row.setProcessSeq("010");
    row.setAlternativeGroupKey(groupKey);
    row.setChildType(childType);
    row.setQtyPerParent(new BigDecimal(qtyPerParent));
    row.setQtyPerTop(new BigDecimal(qtyPerTop));
    row.setBomPurpose("主制造");
    row.setBomVersion("F006");
    row.setEffectiveFrom(LocalDate.of(2026, 5, 21));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    row.setSourceType("U9");
    row.setSourceLineKey("LINE-" + id);
    row.setSourceImportBatchId("IMPORT-1");
    row.setBuildBatchId("BUILD-1");
    return row;
  }

  BomRawHierarchy find(List<BomRawHierarchy> rows, String path) {
    return rows.stream()
        .filter(row -> path.equals(row.getPath()))
        .findFirst()
        .orElseThrow();
  }

  private BomAlternativeCandidate candidate(
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
}
