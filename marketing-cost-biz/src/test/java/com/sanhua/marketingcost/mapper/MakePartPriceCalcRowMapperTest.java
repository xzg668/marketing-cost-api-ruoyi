package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link MakePartPriceCalcRowMapper} 真实 DB CRUD 验证。 */
@Tag("integration")
@DisplayName("MakePartPriceCalcRowMapper · lp_make_part_price_calc_row CRUD")
class MakePartPriceCalcRowMapperTest extends BomMapperTestBase {

  private final String batchId = "make_calc_test_" + UUID.randomUUID();

  @Autowired private MakePartPriceCalcRowMapper mapper;

  @BeforeAll
  static void initV98() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        InputStream in = MakePartPriceCalcRowMapperTest.class.getResourceAsStream(
            "/db/V98__make_part_price_calc_row.sql")) {
      assertThat(in).isNotNull();
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String raw : content.split(";")) {
        String sql = raw.trim();
        if (!sql.isEmpty() && !sql.startsWith("--")) {
          stmt.execute(sql);
        }
      }
      addColumnIfMissing(
          stmt,
          "pricing_month",
          "ALTER TABLE lp_make_part_price_calc_row "
              + "ADD COLUMN pricing_month VARCHAR(7) DEFAULT NULL");
      addColumnIfMissing(
          stmt,
          "price_complete",
          "ALTER TABLE lp_make_part_price_calc_row "
              + "ADD COLUMN price_complete TINYINT(1) NOT NULL DEFAULT 0");
    }
  }

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "DELETE FROM lp_make_part_price_calc_row WHERE calc_batch_id = '" + batchId + "'");
    }
  }

  @Test
  @DisplayName("insert/selectById：写入后可按主键查回重量、单价和汇总价")
  void insertAndSelectById() {
    MakePartPriceCalcRow row = newValidRow("203250582", "301050066", "301990317");
    row.setGrossWeightG(new BigDecimal("56380.000000"));
    row.setNetWeightG(new BigDecimal("55000.000000"));
    row.setRawUnitPrice(new BigDecimal("82.95000000"));
    row.setScrapUnitPrice(new BigDecimal("75.66000000"));
    row.setCostPrice(new BigDecimal("4565.12345678"));
    row.setParentTotalCostPrice(new BigDecimal("4565.12345678"));

    int affected = mapper.insert(row);

    assertThat(affected).isEqualTo(1);
    assertThat(row.getId()).isNotNull().isPositive();
    MakePartPriceCalcRow loaded = mapper.selectById(row.getId());
    assertThat(loaded).isNotNull();
    assertThat(loaded.getParentMaterialNo()).isEqualTo("203250582");
    assertThat(loaded.getChildMaterialNo()).isEqualTo("301050066");
    assertThat(loaded.getScrapCode()).isEqualTo("301990317");
    assertThat(loaded.getPricingMonth()).isEqualTo("2026-05");
    assertThat(loaded.getPriceComplete()).isTrue();
    assertThat(loaded.getGrossWeightG()).isEqualByComparingTo(new BigDecimal("56380"));
    assertThat(loaded.getParentTotalCostPrice()).isEqualByComparingTo(new BigDecimal("4565.12345678"));
  }

  @Test
  @DisplayName("selectPage：按批次和父件分页查询")
  void selectPageByBatchAndParent() {
    mapper.insert(newValidRow("P-A", "C-A", "S-A"));
    mapper.insert(newValidRow("P-A", "C-B", "S-B"));
    mapper.insert(newValidRow("P-B", "C-C", "S-C"));

    Page<MakePartPriceCalcRow> page =
        mapper.selectPage(
            new Page<>(1, 10),
            Wrappers.lambdaQuery(MakePartPriceCalcRow.class)
                .eq(MakePartPriceCalcRow::getCalcBatchId, batchId)
                .eq(MakePartPriceCalcRow::getParentMaterialNo, "P-A")
                .orderByAsc(MakePartPriceCalcRow::getChildMaterialNo));

    assertThat(page.getTotal()).isEqualTo(2);
    assertThat(page.getRecords())
        .extracting(MakePartPriceCalcRow::getChildMaterialNo)
        .containsExactly("C-A", "C-B");
  }

  private MakePartPriceCalcRow newValidRow(String parent, String child, String scrap) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setCalcBatchId(batchId);
    row.setBusinessUnitType("COMMERCIAL");
    row.setPricingMonth("2026-05");
    row.setParentMaterialNo(parent);
    row.setParentMaterialName("制造件");
    row.setItemProcessType("原材料加工");
    row.setChildMaterialNo(child);
    row.setChildMaterialName("原材料");
    row.setStockUnit("千克");
    row.setQtyPerParent(new BigDecimal("56.38000000"));
    row.setGrossWeightG(new BigDecimal("56380.000000"));
    row.setNetWeightG(new BigDecimal("55000.000000"));
    row.setRawPriceType("固定价");
    row.setRawUnitPrice(new BigDecimal("82.95000000"));
    row.setScrapCode(scrap);
    row.setScrapName("废料");
    row.setScrapPriceType("固定价");
    row.setScrapUnitPrice(new BigDecimal("75.66000000"));
    row.setOutsourceFee(BigDecimal.ZERO);
    row.setCostPrice(new BigDecimal("4565.12345678"));
    row.setParentTotalCostPrice(new BigDecimal("4565.12345678"));
    row.setPriceComplete(true);
    row.setStatus("OK");
    return row;
  }

  private static void addColumnIfMissing(Statement stmt, String column, String ddl) throws Exception {
    try (var rs = stmt.executeQuery(
        "SELECT 1 FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() "
            + "AND table_name = 'lp_make_part_price_calc_row' "
            + "AND column_name = '" + column + "'")) {
      if (!rs.next()) {
        stmt.executeUpdate(ddl);
      }
    }
  }
}
