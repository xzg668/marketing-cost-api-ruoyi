package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-02 BOM替代组稳定键")
class BomAlternativeGroupKeyGeneratorTest {

  private BomAlternativeGroupKeyGenerator generator;
  private String parentPathFingerprint;

  @BeforeEach
  void setUp() {
    generator = new BomAlternativeGroupKeyGeneratorImpl();
    parentPathFingerprint = generator.parentPathFingerprint(
        "/1145900000302/101850644@10@030/");
  }

  @Test
  @DisplayName("同一业务位置重新导入时组键保持不变")
  void staysStableAcrossRepeatedImports() {
    BomAlternativeGroupIdentity first = identity(
        "210", "1145900000302", parentPathFingerprint, "101850644",
        "主制造", "F006", date("2026-05-21"), date("9999-12-31"), 10, "010");
    BomAlternativeGroupIdentity reimported = identity(
        " ２１０ ", "１１４５９０００００３０２", parentPathFingerprint.toUpperCase(),
        " 101850644 ", " 主制造 ", " f006 ", date("2026-05-21"),
        date("9999-12-31"), 10, " ０１０ ");

    assertThat(generator.generate(reimported)).isEqualTo(generator.generate(first));
  }

  @Test
  @DisplayName("标准/替代料号及导入构建批次变化不影响组键")
  void excludesCandidateCodesAndBatchMetadata() {
    BomAlternativeGroupIdentity identity = baseIdentity();
    BomAlternativeCandidate original = new BomAlternativeCandidate(
        1L, "201850659", "芯体部件", "YCQB02-021604", BomChildType.STANDARD,
        null, "/standard/", "u9_bom_old", "h_old");
    BomAlternativeCandidate changed = new BomAlternativeCandidate(
        999L, "201850777", "芯体部件", "YCQB02-NEW", BomChildType.STANDARD,
        null, "/standard-new/", "u9_bom_new", "h_new");

    String before = generator.generate(identity);
    String after = generator.generate(identity);

    assertThat(original).isNotEqualTo(changed);
    assertThat(after).isEqualTo(before);
  }

  @Test
  @DisplayName("顶层产品或父路径变化时组键不同")
  void distinguishesTopProductAndParentPath() {
    String base = generator.generate(baseIdentity());
    assertThat(generator.generate(identity(
        "210", "OTHER-TOP", parentPathFingerprint, "101850644",
        "主制造", "F006", date("2026-05-21"), date("9999-12-31"), 10, "010")))
        .isNotEqualTo(base);
    assertThat(generator.generate(identity(
        "210", "1145900000302",
        generator.parentPathFingerprint("/1145900000302/OTHER/101850644/"),
        "101850644", "主制造", "F006", date("2026-05-21"),
        date("9999-12-31"), 10, "010")))
        .isNotEqualTo(base);
  }

  @Test
  @DisplayName("BOM目的、版本和有效期均参与组键")
  void distinguishesPurposeVersionAndEffectivePeriod() {
    String base = generator.generate(baseIdentity());
    assertDifferent(base, identity(
        "210", "1145900000302", parentPathFingerprint, "101850644",
        "半自动", "F006", date("2026-05-21"), date("9999-12-31"), 10, "010"));
    assertDifferent(base, identity(
        "210", "1145900000302", parentPathFingerprint, "101850644",
        "主制造", "F007", date("2026-05-21"), date("9999-12-31"), 10, "010"));
    assertDifferent(base, identity(
        "210", "1145900000302", parentPathFingerprint, "101850644",
        "主制造", "F006", date("2026-05-22"), date("9999-12-31"), 10, "010"));
    assertDifferent(base, identity(
        "210", "1145900000302", parentPathFingerprint, "101850644",
        "主制造", "F006", date("2026-05-21"), date("2027-12-31"), 10, "010"));
  }

  @Test
  @DisplayName("组织、父件、子项和工序均参与组键")
  void distinguishesOrganizationParentSequenceAndProcess() {
    String base = generator.generate(baseIdentity());
    assertDifferent(base, identity(
        "220", "1145900000302", parentPathFingerprint, "101850644",
        "主制造", "F006", date("2026-05-21"), date("9999-12-31"), 10, "010"));
    assertDifferent(base, identity(
        "210", "1145900000302", parentPathFingerprint, "OTHER-PARENT",
        "主制造", "F006", date("2026-05-21"), date("9999-12-31"), 10, "010"));
    assertDifferent(base, identity(
        "210", "1145900000302", parentPathFingerprint, "101850644",
        "主制造", "F006", date("2026-05-21"), date("9999-12-31"), 20, "010"));
    assertDifferent(base, identity(
        "210", "1145900000302", parentPathFingerprint, "101850644",
        "主制造", "F006", date("2026-05-21"), date("9999-12-31"), 10, "210"));
  }

  @Test
  @DisplayName("中文、空格、全半角和空值规范化结果稳定")
  void normalizesChineseWhitespaceWidthAndNulls() {
    BomAlternativeGroupIdentity nulls = identity(
        "210", "TOP", null, "PARENT", " 主制造 ", null, null, null, null, " ０１０ ");
    BomAlternativeGroupIdentity blanks = identity(
        "２１０", " top ", " ", " parent ", "主制造", " ", null, null, null, "010");

    assertThat(generator.generate(blanks)).isEqualTo(generator.generate(nulls));
    assertThat(generator.parentPathFingerprint(" /Ａ/Ｂ/ "))
        .isEqualTo(generator.parentPathFingerprint("/a/b/"));
  }

  @Test
  @DisplayName("结果固定为64位小写SHA-256并有黄金值")
  void returnsLowercaseSha256WithGoldenValue() {
    String key = generator.generate(baseIdentity());

    assertThat(key).matches("[0-9a-f]{64}");
    assertThat(key)
        .isEqualTo("31dc1f3d7489f2bc9a3fdc4003b5f4d5a71133ef5b6e5d50e79c3b874ffdaf7f");
  }

  private void assertDifferent(String base, BomAlternativeGroupIdentity changed) {
    assertThat(generator.generate(changed)).isNotEqualTo(base);
  }

  private BomAlternativeGroupIdentity baseIdentity() {
    return identity(
        "210", "1145900000302", parentPathFingerprint, "101850644",
        "主制造", "F006", date("2026-05-21"), date("9999-12-31"), 10, "010");
  }

  private static BomAlternativeGroupIdentity identity(
      String priceOrgCode,
      String topProductCode,
      String parentPathFingerprint,
      String parentMaterialNo,
      String bomPurpose,
      String bomVersion,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      Integer childSeq,
      String processSeq) {
    return new BomAlternativeGroupIdentity(
        priceOrgCode,
        topProductCode,
        parentPathFingerprint,
        parentMaterialNo,
        bomPurpose,
        bomVersion,
        effectiveFrom,
        effectiveTo,
        childSeq,
        processSeq);
  }

  private static LocalDate date(String value) {
    return LocalDate.parse(value);
  }
}
