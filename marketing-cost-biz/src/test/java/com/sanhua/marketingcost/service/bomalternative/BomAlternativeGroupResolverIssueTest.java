package com.sanhua.marketingcost.service.bomalternative;

import static com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolverTest.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-04 BOM替代组结构异常")
class BomAlternativeGroupResolverIssueTest {

  private final BomAlternativeGroupResolver resolver =
      new BomAlternativeGroupResolverImpl(new BomAlternativeGroupKeyGeneratorImpl());

  @Test
  @DisplayName("有替代但没有标准件时阻断，不任选替代件")
  void blocksAlternativeWithoutStandard() {
    BomAlternativeGroupResolution result =
        resolver.resolve(
            List.of(
                row(2L, "GROUP-A", "ALTERNATIVE", "ALT", "替代件", "主制造", "F006",
                    "/TOP/PARENT@10@030/ALT@10@010/", 10, "010", "1")));

    assertThat(result.groups()).isEmpty();
    assertThat(result.issues())
        .extracting(BomAlternativeGroupIssue::code)
        .contains("ALT_STANDARD_MISSING");
    assertThat(result.issues().get(0).message()).contains("没有明确标准件", "U9");
  }

  @Test
  @DisplayName("多个标准件时阻断，不把第一条当默认标准")
  void blocksMultipleStandards() {
    BomAlternativeGroupResolution result =
        resolver.resolve(
            List.of(
                row(1L, "GROUP-A", "STANDARD", "STD-1", "标准件1", "主制造", "F006",
                    "/TOP/PARENT@10@030/STD-1@10@010/", 10, "010", "1"),
                row(2L, "GROUP-A", "STANDARD", "STD-2", "标准件2", "主制造", "F006",
                    "/TOP/PARENT@10@030/STD-2@10@010/", 10, "010", "1"),
                row(3L, "GROUP-A", "ALTERNATIVE", "ALT", "替代件", "主制造", "F006",
                    "/TOP/PARENT@10@030/ALT@10@010/", 10, "010", "1")));

    assertThat(result.groups()).isEmpty();
    assertThat(result.issues())
        .extracting(BomAlternativeGroupIssue::code)
        .contains("ALT_MULTIPLE_STANDARD");
  }

  @Test
  @DisplayName("同料号重复候选按来源业务行识别并阻断，不静默合并")
  void blocksDuplicateCandidateMaterial() {
    BomRawHierarchy standard =
        row(1L, "GROUP-A", "STANDARD", "STD", "标准件", "主制造", "F006",
            "/TOP/PARENT@10@030/STD@10@010/", 10, "010", "1");
    BomRawHierarchy first =
        row(2L, "GROUP-A", "ALTERNATIVE", "ALT", "替代件", "主制造", "F006",
            "/TOP/PARENT@10@030/ALT@10@010/", 10, "010", "1");
    BomRawHierarchy duplicate =
        row(3L, "GROUP-A", "ALTERNATIVE", "ALT", "替代件重复", "主制造", "F006",
            "/TOP/PARENT@10@030/ALT@10@010-DUP/", 10, "010", "1");
    duplicate.setSourceLineKey("SOURCE-LINE-DUPLICATE");

    BomAlternativeGroupResolution result =
        resolver.resolve(List.of(standard, first, duplicate));

    assertThat(result.groups()).isEmpty();
    BomAlternativeGroupIssue issue =
        result.issues().stream()
            .filter(item -> "ALT_DUPLICATE_CANDIDATE".equals(item.code()))
            .findFirst()
            .orElseThrow();
    assertThat(issue.candidateMaterialCode()).isEqualTo("ALT");
    assertThat(issue.sourceLineKey()).isEqualTo("SOURCE-LINE-DUPLICATE");
  }

  @Test
  @DisplayName("含替代成员的组出现UNKNOWN或NORMAL类型时阻断")
  void blocksUnknownChildTypesInsideAlternativeGroup() {
    BomAlternativeGroupResolution result =
        resolver.resolve(
            List.of(
                row(1L, "GROUP-A", "STANDARD", "STD", "标准件", "主制造", "F006",
                    "/TOP/PARENT@10@030/STD@10@010/", 10, "010", "1"),
                row(2L, "GROUP-A", "ALTERNATIVE", "ALT", "替代件", "主制造", "F006",
                    "/TOP/PARENT@10@030/ALT@10@010/", 10, "010", "1"),
                row(3L, "GROUP-A", "UNKNOWN", "UNKNOWN", "未知类型", "主制造", "F006",
                    "/TOP/PARENT@10@030/UNKNOWN@10@010/", 10, "010", "1"),
                row(4L, "GROUP-A", "NORMAL", "NORMAL", "错误普通类型", "主制造", "F006",
                    "/TOP/PARENT@10@030/NORMAL@10@010/", 10, "010", "1")));

    assertThat(result.groups()).isEmpty();
    assertThat(result.issues())
        .filteredOn(issue -> "ALT_UNKNOWN_CHILD_TYPE".equals(issue.code()))
        .extracting(BomAlternativeGroupIssue::candidateMaterialCode)
        .containsExactlyInAnyOrder("UNKNOWN", "NORMAL");
  }

  @Test
  @DisplayName("替代件缺少组键时阻断并提示用最新U9批次重建")
  void blocksAlternativeWithoutGroupKey() {
    BomRawHierarchy alternative =
        row(2L, null, "ALTERNATIVE", "ALT", "替代件", "主制造", "F006",
            "/TOP/PARENT@10@030/ALT@10@010/", 10, "010", "1");

    BomAlternativeGroupResolution result = resolver.resolve(List.of(alternative));

    assertThat(result.groups()).isEmpty();
    BomAlternativeGroupIssue issue = result.issues().get(0);
    assertThat(issue.code()).isEqualTo("ALT_GROUP_KEY_MISSING");
    assertThat(issue.message()).contains("组键", "最新U9批次重建");
  }

  @Test
  @DisplayName("同一组键混入不同目的、版本、有效期、父路径、序号或工序时阻断")
  void blocksMembersWithMismatchedBusinessScope() {
    BomRawHierarchy standard =
        row(1L, "BROKEN-GROUP", "STANDARD", "STD", "标准件", "主制造", "F006",
            "/TOP/PARENT@10@030/STD@10@010/", 10, "010", "1");
    BomRawHierarchy differentPurpose =
        row(2L, "BROKEN-GROUP", "ALTERNATIVE", "ALT-PURPOSE", "替代目的", "半自动", "F006",
            "/TOP/PARENT@10@030/ALT-PURPOSE@10@010/", 10, "010", "1");
    BomRawHierarchy differentVersion =
        row(3L, "BROKEN-GROUP", "ALTERNATIVE", "ALT-VERSION", "替代版本", "主制造", "F007",
            "/TOP/PARENT@10@030/ALT-VERSION@10@010/", 10, "010", "1");
    BomRawHierarchy differentPath =
        row(4L, "BROKEN-GROUP", "ALTERNATIVE", "ALT-PATH", "替代路径", "主制造", "F006",
            "/TOP/OTHER@20@040/PARENT@10@030/ALT-PATH@10@010/", 10, "010", "1");
    BomRawHierarchy differentSeq =
        row(5L, "BROKEN-GROUP", "ALTERNATIVE", "ALT-SEQ", "替代项次", "主制造", "F006",
            "/TOP/PARENT@10@030/ALT-SEQ@20@010/", 20, "010", "1");
    BomRawHierarchy differentProcess =
        row(6L, "BROKEN-GROUP", "ALTERNATIVE", "ALT-PROC", "替代工序", "主制造", "F006",
            "/TOP/PARENT@10@030/ALT-PROC@10@020/", 10, "020", "1");
    BomRawHierarchy differentEffective =
        row(7L, "BROKEN-GROUP", "ALTERNATIVE", "ALT-DATE", "替代有效期", "主制造", "F006",
            "/TOP/PARENT@10@030/ALT-DATE@10@010/", 10, "010", "1");
    differentEffective.setEffectiveFrom(java.time.LocalDate.of(2026, 6, 1));

    BomAlternativeGroupResolution result =
        resolver.resolve(
            List.of(
                standard,
                differentPurpose,
                differentVersion,
                differentPath,
                differentSeq,
                differentProcess,
                differentEffective));

    assertThat(result.groups()).isEmpty();
    assertThat(result.issues())
        .filteredOn(issue -> "ALT_MEMBER_SCOPE_MISMATCH".equals(issue.code()))
        .hasSizeGreaterThanOrEqualTo(6);
  }

  @Test
  @DisplayName("异常明细携带业务位置、候选、原始类型和两级来源ID")
  void issueCarriesCompleteBusinessAndTraceContext() {
    BomRawHierarchy alternative =
        row(22L, "GROUP-A", "ALTERNATIVE", "ALT", "替代件", "主制造", "F006",
            "/TOP/PARENT@10@030/ALT@10@010/", 10, "010", "1");
    alternative.setSourceU9RowId(283417L);

    BomAlternativeGroupIssue issue =
        resolver.resolve(List.of(alternative)).issues().get(0);

    assertThat(issue.topProductCode()).isEqualTo("TOP");
    assertThat(issue.parentMaterialNo()).isEqualTo("PARENT");
    assertThat(issue.parentPath()).isEqualTo("/TOP/PARENT@10@030/");
    assertThat(issue.bomPurpose()).isEqualTo("主制造");
    assertThat(issue.bomVersion()).isEqualTo("F006");
    assertThat(issue.childSeq()).isEqualTo(10);
    assertThat(issue.processSeq()).isEqualTo("010");
    assertThat(issue.candidateMaterialCode()).isEqualTo("ALT");
    assertThat(issue.rawChildType()).isEqualTo("ALTERNATIVE");
    assertThat(issue.rawHierarchyNodeId()).isEqualTo(22L);
    assertThat(issue.sourceU9RowId()).isEqualTo(283417L);
  }

  @Test
  @DisplayName("异常构造的BomAlternativeGroup不能偷偷返回第一个标准件")
  void groupDoesNotChooseStandardWhenStructureIsInvalid() {
    BomAlternativeCandidate first =
        new BomAlternativeCandidate(
            1L, "STD-1", "标准1", null, BomChildType.STANDARD,
            java.math.BigDecimal.ONE, "/STD-1/", "I", "B");
    BomAlternativeCandidate second =
        new BomAlternativeCandidate(
            2L, "STD-2", "标准2", null, BomChildType.STANDARD,
            java.math.BigDecimal.ONE, "/STD-2/", "I", "B");
    BomAlternativeGroup group =
        new BomAlternativeGroup(null, "BROKEN", List.of(first, second));

    assertThatThrownBy(group::standardCandidate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("恰好一个标准件");
  }
}
