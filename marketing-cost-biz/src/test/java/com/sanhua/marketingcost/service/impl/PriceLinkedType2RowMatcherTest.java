package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchSummary;
import com.sanhua.marketingcost.dto.PriceLinkedType2StandardRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.enums.PriceLinkedType2RowMatchStatus;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-04 类型2以 Sheet1 为主的分级匹配")
class PriceLinkedType2RowMatcherTest {

  private static final Path TYPE2_SAMPLE = Path.of(
      "/Users/xiexicheng/Desktop/price/采购价表二次开发导入模板-股份251115联动价格导入类型2.xls");

  private final PriceLinkedType2TextNormalizerImpl normalizer =
      new PriceLinkedType2TextNormalizerImpl();
  private final PriceLinkedType2RowMatcherImpl matcher =
      new PriceLinkedType2RowMatcherImpl(normalizer);

  @Test
  @DisplayName("料号唯一时不要求供应商名称完全相同")
  void matchesOneBusinessRowToOneStandardRow() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("109910977", "浙江华亿", 6)),
        List.of(standard("109910977", "浙江华亿有限公司", "S001", 2)));

    assertThat(result.getMatchedCount()).isEqualTo(1);
    assertThat(result.getBlockedCount()).isZero();
    assertThat(result.getMatchedResults().getFirst().getMatchedStandardRow()
        .getSupplierCode()).isEqualTo("S001");
  }

  @Test
  @DisplayName("首尾和连续空格差异仍可精确匹配")
  void normalizesOuterAndRepeatedWhitespace() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product(" 109910977 ", " 浙江   华亿 ", 6)),
        List.of(standard("109910977", "浙江 华亿", "S001", 2)));

    assertThat(result.getMatchedCount()).isEqualTo(1);
    assertThat(result.getMatchedResults().getFirst().getMatchKey().getSupplierName())
        .isEqualTo("浙江 华亿");
  }

  @Test
  @DisplayName("全半角和英文大小写差异可标准化后匹配")
  void normalizesFullWidthCharactersAndEnglishCase() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("ＡＢＣ１２３", "ＺｈｅＪｉａｎｇ　ＨｕａＹｉ", 6)),
        List.of(standard("abc123", "zhejiang huayi", "S001", 2)));

    assertThat(result.getMatchedCount()).isEqualTo(1);
    assertThat(result.getMatchedResults().getFirst().getMatchKey().asText())
        .isEqualTo("ABC123 | ZHEJIANG HUAYI");
  }

  @Test
  @DisplayName("同料号多条时先按供应商名称精确匹配")
  void matchesDuplicateMaterialByExactSupplier() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("1001", "供应商甲", 6)),
        List.of(
            standard("1001", "供应商乙", "S002", 2),
            standard("1001", "供应商甲", "S001", 3)));

    assertThat(result.getMatchedCount()).isEqualTo(1);
    assertThat(result.getMatchedResults().getFirst().getMatchedStandardRow()
        .getSupplierCode()).isEqualTo("S001");
  }

  @Test
  @DisplayName("同料号精确名称匹配不到时允许唯一简称模糊匹配")
  void matchesDuplicateMaterialByUniqueFuzzySupplierName() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("1001", "浙江华亿", 6)),
        List.of(
            standard("1001", "上海其他公司", "S002", 2),
            standard("1001", "浙江华亿有限公司", "S001", 3)));

    assertThat(result.getMatchedCount()).isEqualTo(1);
    assertThat(result.getMatchedResults().getFirst().getMatchedStandardRow()
        .getSupplierCode()).isEqualTo("S001");
  }

  @Test
  @DisplayName("同料号同供应商对应多个不同代码时阻断且不任选")
  void blocksAmbiguousSupplierCodes() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("1001", "供应商甲", 6)),
        List.of(
            standard("1001", "供应商甲", "S001", 2),
            standard("1001", "供应商甲", "S002", 3)));

    assertThat(result.getMatchedCount()).isZero();
    assertThat(result.getResults()).singleElement()
        .satisfies(match -> {
          assertThat(match.getStatus())
              .isEqualTo(PriceLinkedType2RowMatchStatus.STANDARD_DUPLICATE);
          assertThat(match.getStandardRows()).hasSize(2);
          assertThat(match.getMessage()).contains("[2, 3]");
        });
  }

  @Test
  @DisplayName("业务计算 Sheet 匹配键重复时阻断且不任选")
  void blocksDuplicateBusinessRows() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(
            product("1001", "供应商甲", 6),
            product("1001", "供应商甲", 7)),
        List.of(standard("1001", "供应商甲", "S001", 2)));

    assertThat(result.getMatchedCount()).isZero();
    assertThat(result.getResults()).singleElement()
        .satisfies(match -> {
          assertThat(match.getStatus())
              .isEqualTo(PriceLinkedType2RowMatchStatus.BUSINESS_DUPLICATE);
          assertThat(match.getBusinessRows()).hasSize(2);
          assertThat(match.getMessage()).contains("[6, 7]");
        });
  }

  @Test
  @DisplayName("两侧同时重复时明确返回双侧重复")
  void blocksDuplicatesOnBothSides() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(
            product("1001", "供应商甲", 6),
            product("1001", "供应商甲", 7)),
        List.of(
            standard("1001", "供应商甲", "S001", 2),
            standard("1001", "供应商甲", "S001", 3)));

    assertThat(result.getResults()).singleElement()
        .extracting(PriceLinkedType2RowMatchResult::getStatus)
        .isEqualTo(PriceLinkedType2RowMatchStatus.BOTH_DUPLICATE);
  }

  @Test
  @DisplayName("ImportData多出的料号不导入也不报错")
  void ignoresUnusedImportDataRows() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("1001", "供应商甲", 6)),
        List.of(
            standard("1001", "供应商甲", "S001", 2),
            standard("1002", "供应商乙", "S002", 3)));

    assertThat(result.getMatchedCount()).isEqualTo(1);
    assertThat(result.getBlockedCount()).isZero();
    assertThat(result.getResults()).hasSize(1);
  }

  @Test
  @DisplayName("供应商代码为空时即使料号和名称匹配也阻断")
  void blocksMatchedRowWhenSupplierCodeIsBlank() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("1001", "供应商甲", 6)),
        List.of(standard("1001", "供应商甲", "  ", 2)));

    assertThat(result.getMatchedCount()).isZero();
    assertThat(result.getResults()).singleElement()
        .extracting(PriceLinkedType2RowMatchResult::getStatus)
        .isEqualTo(PriceLinkedType2RowMatchStatus.MISSING_SUPPLIER_CODE);
  }

  @Test
  @DisplayName("料号缺失时按供应商简称匹配ImportData公共字段")
  void fallsBackToUniqueSupplierNameWhenMaterialIsMissing() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("AAA", "浙江华亿", 6)),
        List.of(standard("1001", "浙江华亿有限公司", "S001", 2)));

    assertThat(result.getMatchedCount()).isEqualTo(1);
    assertThat(result.getBlockedCount()).isZero();
    assertThat(result.getMatchedResults().getFirst()).satisfies(match -> {
      assertThat(match.getStatus())
          .isEqualTo(PriceLinkedType2RowMatchStatus.MATCHED_SUPPLIER_FALLBACK);
      assertThat(match.getMatchedStandardRow().getSupplierCode()).isEqualTo("S001");
    });
  }

  @Test
  @DisplayName("优先用已按料号匹配成功的同名供应商给缺料号行补代码")
  void reusesSupplierCodeResolvedByAnotherSheet1Row() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(
            product("B", "浙江华亿", 6),
            product("AAA", "浙江华亿", 7)),
        List.of(
            standard("B", "浙江华亿有限公司", "S001", 2),
            standard("OTHER", "浙江华亿股份有限公司", "S999", 3)));

    assertThat(result.getMatchedCount()).isEqualTo(2);
    assertThat(result.getBlockedCount()).isZero();
    assertThat(result.getMatchedResults()).allSatisfy(match ->
        assertThat(match.getMatchedStandardRow().getSupplierCode()).isEqualTo("S001"));
  }

  @Test
  @DisplayName("料号和供应商名称都无法取得供应商代码时阻断")
  void blocksWhenNeitherMaterialNorSupplierCanResolveCode() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("AAA", "浙江华亿", 6)),
        List.of(standard("OTHER", "上海其他公司", "S002", 2)));

    assertThat(result.getMatchedCount()).isZero();
    assertThat(result.getBlockedCount()).isEqualTo(1);
    assertThat(result.getBlockedResults().getFirst()).satisfies(match -> {
      assertThat(match.getStatus())
          .isEqualTo(PriceLinkedType2RowMatchStatus.MISSING_SUPPLIER_CODE);
      assertThat(match.getMessage()).contains("料号和供应商名称");
    });
  }

  @Test
  @DisplayName("Sheet1缺少料号或供应商名称时单独阻断")
  void blocksInvalidKeysIndividually() {
    PriceLinkedType2RowMatchSummary result = match(
        List.of(product("1001", null, 6)),
        List.of(standard("1001", null, "S001", 2)));

    assertThat(result.getResults()).extracting("status")
        .containsExactly(PriceLinkedType2RowMatchStatus.INVALID_BUSINESS_KEY);
  }

  @Test
  @DisplayName("真实类型2文件以50条Sheet1行为准且忽略ImportData多余行")
  void matchesEverySheet1RowInRealWorkbook() throws Exception {
    assertThat(Files.exists(TYPE2_SAMPLE)).as("真实类型2样例存在").isTrue();
    PriceLinkedType2WorkbookParserImpl parser =
        new PriceLinkedType2WorkbookParserImpl(new PriceLinkedWorkbookTypeDetectorImpl());
    PriceLinkedType2WorkbookParseResult workbook = parser.parse(
        new ByteArrayInputStream(Files.readAllBytes(TYPE2_SAMPLE)),
        TYPE2_SAMPLE.getFileName().toString());

    PriceLinkedType2RowMatchSummary result = matcher.match(workbook);
    long explainedBusinessRows = result.getResults().stream()
        .mapToLong(match -> match.getBusinessRows().size())
        .sum();
    Map<PriceLinkedType2RowMatchStatus, Long> statusCounts = result.getResults().stream()
        .collect(Collectors.groupingBy(
            PriceLinkedType2RowMatchResult::getStatus,
            Collectors.counting()));

    assertThat(explainedBusinessRows).isEqualTo(workbook.getProductRows().size());
    assertThat(result.getResults()).hasSize(workbook.getProductRows().size());
    assertThat(result.getMatchedCount()).isEqualTo(50);
    assertThat(result.getBlockedCount()).isZero();
    assertThat(statusCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
        PriceLinkedType2RowMatchStatus.MATCHED, 40L,
        PriceLinkedType2RowMatchStatus.MATCHED_SUPPLIER_FALLBACK, 10L));
    assertThat(result.getMatchedResults()).allSatisfy(match -> {
      assertThat(match.getBusinessRows()).hasSize(1);
      assertThat(match.getStandardRows()).hasSize(1);
      assertThat(match.getMatchedStandardRow().getSupplierCode()).isNotBlank();
    });
  }

  private PriceLinkedType2RowMatchSummary match(
      List<PriceLinkedType2ProductRow> products,
      List<PriceLinkedType2StandardRow> standards) {
    return matcher.match(workbook(products, standards));
  }

  private PriceLinkedType2WorkbookParseResult workbook(
      List<PriceLinkedType2ProductRow> products,
      List<PriceLinkedType2StandardRow> standards) {
    return new PriceLinkedType2WorkbookParseResult(
        "test.xlsx",
        "业务",
        1,
        "标准",
        1,
        List.of(),
        products,
        standards,
        List.of());
  }

  private PriceLinkedType2ProductRow product(
      String materialCode, String supplierName, int rowNumber) {
    return new PriceLinkedType2ProductRow(
        "业务",
        rowNumber,
        materialCode,
        "产品",
        "规格",
        "只",
        supplierName,
        "A1+B1",
        "R" + rowNumber,
        null,
        null,
        List.of());
  }

  private PriceLinkedType2StandardRow standard(
      String materialCode,
      String supplierName,
      String supplierCode,
      int rowNumber) {
    return new PriceLinkedType2StandardRow(
        "标准",
        rowNumber,
        materialCode,
        supplierName,
        supplierCode,
        List.of());
  }
}
