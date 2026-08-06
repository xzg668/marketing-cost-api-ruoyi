package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchSummary;
import com.sanhua.marketingcost.dto.PriceLinkedType2StandardRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-04 类型2匹配行字段合并")
class PriceLinkedType2MergedRowMapperTest {

  private final PriceLinkedType2TextNormalizerImpl normalizer =
      new PriceLinkedType2TextNormalizerImpl();
  private final PriceLinkedType2RowMatcherImpl matcher =
      new PriceLinkedType2RowMatcherImpl(normalizer);
  private final PriceLinkedType2MergedRowMapperImpl mapper =
      new PriceLinkedType2MergedRowMapperImpl(normalizer);

  @Test
  @DisplayName("公式和输入来自业务 Sheet，供应商与标准属性来自标准 Sheet")
  void mergesFieldsFromTheirAuthoritativeSheets() {
    PriceLinkedType2CellSnapshot input = new PriceLinkedType2CellSnapshot(
        "业务", "G6", "黄铜毛重(g)", "10.00", new BigDecimal("10"), null, "g");
    PriceLinkedType2ProductRow business = product(
        "ＡＢＣ１２３", "供应商甲", 6, "G6*$E$2", List.of(input));
    PriceLinkedType2StandardRow standard = standard(
        "abc123",
        "供应商甲",
        "S001",
        2,
        cell("组织", "股份", "A2"),
        cell("来源", "采购", "B2"),
        cell("采购分类", "铜件", "C2"),
        cell("是否含税", "FALSE", "D2"),
        cell("生效日期", "2026-07-01", "E2"),
        cell("失效日期", "2026-07-31", "F2"));

    List<PriceLinkedType2MergedRow> result = mapper.map(
        matcher.match(workbook(List.of(business), List.of(standard))),
        YearMonth.of(2026, 7));

    assertThat(result).singleElement().satisfies(row -> {
      assertThat(row.getBusinessRow()).isSameAs(business);
      assertThat(row.getStandardRow()).isSameAs(standard);
      assertThat(row.getSourceFormula()).isEqualTo("G6*$E$2");
      assertThat(row.getInputSnapshots()).containsExactly(input);
      assertThat(row.getMaterialCode()).isEqualTo("ＡＢＣ１２３");
      assertThat(row.getSupplierName()).isEqualTo("供应商甲");
      assertThat(row.getSupplierCode()).isEqualTo("S001");
      assertThat(row.getBusinessUnit()).isEqualTo("股份");
      assertThat(row.getSource()).isEqualTo("采购");
      assertThat(row.getMaterialAttribute()).isEqualTo("铜件");
      assertThat(row.getTaxIncludedText()).isEqualTo("FALSE");
      assertThat(row.getEffectiveDateText()).isEqualTo("2026-07-01");
      assertThat(row.getExpiryDateText()).isEqualTo("2026-07-31");
      assertThat(row.getBusinessIdentityKey())
          .isEqualTo("股份 | 2026-07 | ABC123 | S001");
    });
  }

  @Test
  @DisplayName("料号缺失时使用同供应商公共字段并生成上月有效期")
  void mapsSupplierFallbackAndGeneratesPreviousMonthDates() {
    PriceLinkedType2RowMatchSummary matches = matcher.match(workbook(
        List.of(
            product("B", "供应商甲", 6, "A1", List.of()),
            product("AAA", "供应商甲", 7, "A2", List.of())),
        List.of(standard(
            "B",
            "供应商甲有限公司",
            "S001",
            2,
            cell("组织", "股份", "A2"),
            cell("是否含税", "FALSE", "D2"),
            cell("生效日期", "2026-05-01", "E2"),
            cell("失效日期", "2026-05-31", "F2")))));

    List<PriceLinkedType2MergedRow> result =
        mapper.map(matches, YearMonth.of(2026, 7));

    assertThat(result).hasSize(2);
    assertThat(result).filteredOn(row -> "B".equals(row.getMaterialCode()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getEffectiveDateText()).isEqualTo("2026-05-01");
          assertThat(row.getExpiryDateText()).isEqualTo("2026-05-31");
          assertThat(row.isSupplierFallback()).isFalse();
        });
    assertThat(result).filteredOn(row -> "AAA".equals(row.getMaterialCode()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getSupplierCode()).isEqualTo("S001");
          assertThat(row.getTaxIncludedText()).isEqualTo("FALSE");
          assertThat(row.getEffectiveDateText()).isEqualTo("2026-06-01");
          assertThat(row.getExpiryDateText()).isEqualTo("2026-06-30");
          assertThat(row.isSupplierFallback()).isTrue();
        });
  }

  @Test
  @DisplayName("同料号不同供应商代码形成不同业务身份")
  void createsDifferentBusinessIdentityForDifferentSupplierCodes() {
    PriceLinkedType2RowMatchSummary matches = matcher.match(workbook(
        List.of(
            product("1001", "供应商甲", 6, "A1", List.of()),
            product("1001", "供应商乙", 7, "A2", List.of())),
        List.of(
            standard("1001", "供应商甲", "S001", 2, cell("组织", "股份", "A2")),
            standard("1001", "供应商乙", "S002", 3, cell("组织", "股份", "A3")))));

    List<PriceLinkedType2MergedRow> result =
        mapper.map(matches, YearMonth.of(2026, 7));

    assertThat(result).extracting("supplierCode").containsExactly("S001", "S002");
    assertThat(result).extracting("businessIdentityKey")
        .containsExactly(
            "股份 | 2026-07 | 1001 | S001",
            "股份 | 2026-07 | 1001 | S002");
  }

  @Test
  @DisplayName("核算月份参与最终业务身份")
  void pricingMonthIsPartOfBusinessIdentity() {
    PriceLinkedType2RowMatchSummary matches = matcher.match(workbook(
        List.of(product("1001", "供应商甲", 6, "A1", List.of())),
        List.of(standard(
            "1001", "供应商甲", "S001", 2, cell("组织", "股份", "A2")))));

    PriceLinkedType2MergedRow july =
        mapper.map(matches, YearMonth.of(2026, 7)).getFirst();
    PriceLinkedType2MergedRow august =
        mapper.map(matches, YearMonth.of(2026, 8)).getFirst();

    assertThat(july.getBusinessIdentityKey())
        .isEqualTo("股份 | 2026-07 | 1001 | S001");
    assertThat(august.getBusinessIdentityKey())
        .isEqualTo("股份 | 2026-08 | 1001 | S001");
  }

  @Test
  @DisplayName("映射结果只读且核算月份必填")
  void returnsReadOnlyRowsAndRequiresPricingMonth() {
    PriceLinkedType2RowMatchSummary matches = matcher.match(workbook(
        List.of(product("1001", "供应商甲", 6, "A1", List.of())),
        List.of(standard("1001", "供应商甲", "S001", 2))));

    List<PriceLinkedType2MergedRow> result =
        mapper.map(matches, YearMonth.of(2026, 7));

    assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> mapper.map(matches, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("核算月份");
  }

  @Test
  @DisplayName("匹配和合并组件不依赖 Mapper、Repository 或数据库写服务")
  void hasNoPersistenceDependency() {
    assertThat(nonStaticFieldTypes(PriceLinkedType2RowMatcherImpl.class))
        .containsExactly(
            "com.sanhua.marketingcost.service.PriceLinkedType2TextNormalizer");
    assertThat(nonStaticFieldTypes(PriceLinkedType2MergedRowMapperImpl.class))
        .containsExactly(
            "com.sanhua.marketingcost.service.PriceLinkedType2TextNormalizer");
  }

  private List<String> nonStaticFieldTypes(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .map(field -> field.getType().getName())
        .toList();
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
      String materialCode,
      String supplierName,
      int rowNumber,
      String formula,
      List<PriceLinkedType2CellSnapshot> snapshots) {
    return new PriceLinkedType2ProductRow(
        "业务",
        rowNumber,
        materialCode,
        "产品",
        "规格",
        "只",
        supplierName,
        formula,
        "R" + rowNumber,
        new BigDecimal("10"),
        new BigDecimal("8.84955752"),
        snapshots);
  }

  private PriceLinkedType2StandardRow standard(
      String materialCode,
      String supplierName,
      String supplierCode,
      int rowNumber,
      PriceLinkedType2CellSnapshot... cells) {
    return new PriceLinkedType2StandardRow(
        "标准",
        rowNumber,
        materialCode,
        supplierName,
        supplierCode,
        List.of(cells));
  }

  private PriceLinkedType2CellSnapshot cell(
      String header, String value, String cellRef) {
    return new PriceLinkedType2CellSnapshot(
        "标准", cellRef, header, value, null, null, null);
  }
}
