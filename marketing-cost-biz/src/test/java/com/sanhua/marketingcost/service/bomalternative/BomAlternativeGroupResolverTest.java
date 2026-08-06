package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-04 BOM替代组识别")
class BomAlternativeGroupResolverTest {

  private final BomAlternativeGroupResolver resolver =
      new BomAlternativeGroupResolverImpl(new BomAlternativeGroupKeyGeneratorImpl());

  @Test
  @DisplayName("一个标准一个替代识别为一组，并明确输出唯一标准和替代候选")
  void resolvesOneStandardAndOneAlternative() {
    BomAlternativeGroupResolution result =
        resolver.resolve(
            List.of(
                row(1L, "GROUP-A", "STANDARD", "STD", "标准件", "主制造", "F006",
                    "/TOP/PARENT@10@030/STD@10@010/", 10, "010", "1"),
                row(2L, "GROUP-A", "ALTERNATIVE", "ALT", "替代件", "主制造", "F006",
                    "/TOP/PARENT@10@030/ALT@10@010/", 10, "010", "1")));

    assertThat(result.hasBlockingIssues()).isFalse();
    assertThat(result.groups()).hasSize(1);
    BomAlternativeGroup group = result.groups().get(0);
    assertThat(group.standardCandidate().materialCode()).isEqualTo("STD");
    assertThat(group.alternativeCandidates())
        .extracting(BomAlternativeCandidate::materialCode)
        .containsExactly("ALT");
    assertThat(group.candidates()).hasSize(2);
  }

  @Test
  @DisplayName("一个标准两个替代仍识别为一组，两个替代全部保留")
  void resolvesOneStandardAndMultipleAlternatives() {
    BomAlternativeGroupResolution result =
        resolver.resolve(
            List.of(
                row(1L, "GROUP-A", "STANDARD", "STD", "标准件", "主制造", "F006",
                    "/TOP/PARENT@10@030/STD@10@010/", 10, "010", "1"),
                row(2L, "GROUP-A", "ALTERNATIVE", "ALT-1", "替代件1", "主制造", "F006",
                    "/TOP/PARENT@10@030/ALT-1@10@010/", 10, "010", "1"),
                row(3L, "GROUP-A", "ALTERNATIVE", "ALT-2", "替代件2", "主制造", "F006",
                    "/TOP/PARENT@10@030/ALT-2@10@010/", 10, "010", "1")));

    assertThat(result.groups()).hasSize(1);
    assertThat(result.groups().get(0).alternativeCandidates())
        .extracting(BomAlternativeCandidate::materialCode)
        .containsExactly("ALT-1", "ALT-2");
  }

  @Test
  @DisplayName("只有标准件的普通BOM位置不定义为替代组")
  void ignoresStandardOnlyPosition() {
    BomAlternativeGroupResolution result =
        resolver.resolve(
            List.of(
                row(1L, "ORDINARY-GROUP-KEY", "STANDARD", "ONLY", "普通标准件", "主制造",
                    "F006", "/TOP/PARENT@10@030/ONLY@20@020/", 20, "020", "1")));

    assertThat(result.groups()).isEmpty();
    assertThat(result.issues()).isEmpty();
  }

  @Test
  @DisplayName("标准和替代用量不同仍按业务位置成组，不根据用量拆组")
  void groupsCandidatesWithDifferentQuantities() {
    BomAlternativeGroupResolution result =
        resolver.resolve(
            List.of(
                row(1L, "GROUP-A", "STANDARD", "STD", "标准件", "主制造", "F006",
                    "/TOP/PARENT@10@030/STD@10@010/", 10, "010", "1"),
                row(2L, "GROUP-A", "ALTERNATIVE", "ALT", "替代件", "主制造", "F006",
                    "/TOP/PARENT@10@030/ALT@10@010/", 10, "010", "1.25")));

    BomAlternativeGroup group = result.groups().get(0);
    assertThat(group.standardCandidate().qtyPerParent()).isEqualByComparingTo("1");
    assertThat(group.alternativeCandidates().get(0).qtyPerParent())
        .isEqualByComparingTo("1.25");
  }

  @Test
  @DisplayName("不同BOM目的、版本、父路径和工序使用不同组键时互不混组")
  void keepsDifferentBusinessScopesSeparated() {
    List<BomRawHierarchy> rows =
        List.of(
            row(1L, "G-MAKE", "STANDARD", "S-MAKE", "标准", "主制造", "F006",
                "/TOP/PARENT@10@030/S-MAKE@10@010/", 10, "010", "1"),
            row(2L, "G-MAKE", "ALTERNATIVE", "A-MAKE", "替代", "主制造", "F006",
                "/TOP/PARENT@10@030/A-MAKE@10@010/", 10, "010", "1"),
            row(3L, "G-SEMI", "STANDARD", "S-SEMI", "标准", "半自动", "F006",
                "/TOP/PARENT@10@030/S-SEMI@10@010/", 10, "010", "1"),
            row(4L, "G-SEMI", "ALTERNATIVE", "A-SEMI", "替代", "半自动", "F006",
                "/TOP/PARENT@10@030/A-SEMI@10@010/", 10, "010", "1"),
            row(5L, "G-VERSION", "STANDARD", "S-V2", "标准", "主制造", "F007",
                "/TOP/PARENT@10@030/S-V2@10@010/", 10, "010", "1"),
            row(6L, "G-VERSION", "ALTERNATIVE", "A-V2", "替代", "主制造", "F007",
                "/TOP/PARENT@10@030/A-V2@10@010/", 10, "010", "1"),
            row(7L, "G-PATH", "STANDARD", "S-PATH", "标准", "主制造", "F006",
                "/TOP/OTHER@20@040/PARENT@10@030/S-PATH@10@010/", 10, "010", "1"),
            row(8L, "G-PATH", "ALTERNATIVE", "A-PATH", "替代", "主制造", "F006",
                "/TOP/OTHER@20@040/PARENT@10@030/A-PATH@10@010/", 10, "010", "1"),
            row(9L, "G-PROCESS", "STANDARD", "S-PROC", "标准", "主制造", "F006",
                "/TOP/PARENT@10@030/S-PROC@10@020/", 10, "020", "1"),
            row(10L, "G-PROCESS", "ALTERNATIVE", "A-PROC", "替代", "主制造", "F006",
                "/TOP/PARENT@10@030/A-PROC@10@020/", 10, "020", "1"));

    BomAlternativeGroupResolution result = resolver.resolve(rows);

    assertThat(result.hasBlockingIssues()).isFalse();
    assertThat(result.groups())
        .extracting(BomAlternativeGroup::alternativeGroupKey)
        .containsExactly("G-MAKE", "G-PATH", "G-PROCESS", "G-SEMI", "G-VERSION");
  }

  @Test
  @DisplayName("相同业务位置重新导入批次变化时仍解析为同一个稳定组")
  void repeatedImportKeepsStableResolvedGroup() {
    List<BomRawHierarchy> first =
        List.of(
            row(1L, "GROUP-STABLE", "STANDARD", "STD", "标准", "主制造", "F006",
                "/TOP/PARENT@10@030/STD@10@010/", 10, "010", "1"),
            row(2L, "GROUP-STABLE", "ALTERNATIVE", "ALT", "替代", "主制造", "F006",
                "/TOP/PARENT@10@030/ALT@10@010/", 10, "010", "1"));
    List<BomRawHierarchy> rebuilt =
        first.stream()
            .map(
                source -> {
                  BomRawHierarchy copy = copy(source);
                  copy.setId(source.getId() + 100);
                  copy.setSourceU9RowId(source.getSourceU9RowId() + 100);
                  copy.setSourceImportBatchId("IMPORT-NEW");
                  copy.setBuildBatchId("BUILD-NEW");
                  return copy;
                })
            .toList();

    BomAlternativeGroup firstGroup = resolver.resolve(first).groups().get(0);
    BomAlternativeGroup rebuiltGroup = resolver.resolve(rebuilt).groups().get(0);

    assertThat(firstGroup.alternativeGroupKey()).isEqualTo("GROUP-STABLE");
    assertThat(rebuiltGroup.alternativeGroupKey())
        .isEqualTo(firstGroup.alternativeGroupKey());
    assertThat(rebuiltGroup.candidates())
        .extracting(BomAlternativeCandidate::rawHierarchyNodeId)
        .containsExactly(101L, 102L);
  }

  @Test
  @DisplayName("1145900000302主制造只识别一个芯体替代组")
  void resolvesRealPressureTransmitterAlternativeGroup() {
    BomRawHierarchy standard =
        row(
            5001L,
            "REAL-GROUP",
            "STANDARD",
            "201850659",
            "芯体部件",
            "主制造",
            "F006",
            "/1145900000302/101850644@10@030/201850659@10@010/",
            10,
            "010",
            "1");
    standard.setTopProductCode("1145900000302");
    standard.setParentCode("101850644");
    standard.setSourceU9RowId(287987L);
    BomRawHierarchy alternative =
        row(
            5002L,
            "REAL-GROUP",
            "ALTERNATIVE",
            "201850522",
            "芯体部件",
            "主制造",
            "F006",
            "/1145900000302/101850644@10@030/201850522@10@010/",
            10,
            "010",
            "1");
    alternative.setTopProductCode("1145900000302");
    alternative.setParentCode("101850644");
    alternative.setSourceU9RowId(283417L);
    BomRawHierarchy ordinary =
        row(
            5003L,
            "ORDINARY",
            "STANDARD",
            "9830000025705",
            "包装组件",
            "主制造",
            "F006",
            "/1145900000302/9830000025705@20@020/",
            20,
            "020",
            "1");
    ordinary.setTopProductCode("1145900000302");
    ordinary.setParentCode("1145900000302");

    BomAlternativeGroupResolution result =
        resolver.resolve(List.of(standard, alternative, ordinary));

    assertThat(result.groups()).hasSize(1);
    BomAlternativeGroup group = result.groups().get(0);
    assertThat(group.identity().topProductCode()).isEqualTo("1145900000302");
    assertThat(group.identity().parentMaterialNo()).isEqualTo("101850644");
    assertThat(group.standardCandidate().materialCode()).isEqualTo("201850659");
    assertThat(group.alternativeCandidates())
        .extracting(BomAlternativeCandidate::materialCode)
        .containsExactly("201850522");
  }

  static BomRawHierarchy row(
      long id,
      String groupKey,
      String childType,
      String materialCode,
      String materialName,
      String bomPurpose,
      String bomVersion,
      String path,
      int childSeq,
      String processSeq,
      String qty) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setPriceOrgCode("210");
    row.setTopProductCode("TOP");
    row.setParentCode("PARENT");
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialName);
    row.setMaterialSpec("SPEC-" + materialCode);
    row.setLevel(2);
    row.setPath(path);
    row.setSortSeq(childSeq);
    row.setProcessSeq(processSeq);
    row.setChildType(childType);
    row.setAlternativeGroupKey(groupKey);
    row.setQtyPerParent(new BigDecimal(qty));
    row.setQtyPerTop(new BigDecimal(qty));
    row.setBomPurpose(bomPurpose);
    row.setBomVersion(bomVersion);
    row.setEffectiveFrom(LocalDate.of(2026, 5, 21));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    row.setSourceType("U9");
    row.setSourceU9RowId(100_000L + id);
    row.setSourceLineKey("SOURCE-LINE-" + id);
    row.setSourceImportBatchId("IMPORT-OLD");
    row.setBuildBatchId("BUILD-OLD");
    return row;
  }

  private static BomRawHierarchy copy(BomRawHierarchy source) {
    BomRawHierarchy copy = new BomRawHierarchy();
    copy.setId(source.getId());
    copy.setPriceOrgCode(source.getPriceOrgCode());
    copy.setTopProductCode(source.getTopProductCode());
    copy.setParentCode(source.getParentCode());
    copy.setMaterialCode(source.getMaterialCode());
    copy.setMaterialName(source.getMaterialName());
    copy.setMaterialSpec(source.getMaterialSpec());
    copy.setLevel(source.getLevel());
    copy.setPath(source.getPath());
    copy.setSortSeq(source.getSortSeq());
    copy.setProcessSeq(source.getProcessSeq());
    copy.setChildType(source.getChildType());
    copy.setAlternativeGroupKey(source.getAlternativeGroupKey());
    copy.setQtyPerParent(source.getQtyPerParent());
    copy.setQtyPerTop(source.getQtyPerTop());
    copy.setBomPurpose(source.getBomPurpose());
    copy.setBomVersion(source.getBomVersion());
    copy.setEffectiveFrom(source.getEffectiveFrom());
    copy.setEffectiveTo(source.getEffectiveTo());
    copy.setSourceType(source.getSourceType());
    copy.setSourceU9RowId(source.getSourceU9RowId());
    copy.setSourceLineKey(source.getSourceLineKey());
    copy.setSourceImportBatchId(source.getSourceImportBatchId());
    copy.setBuildBatchId(source.getBuildBatchId());
    return copy;
  }
}
