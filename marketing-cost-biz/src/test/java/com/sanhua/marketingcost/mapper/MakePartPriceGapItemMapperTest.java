package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.MakePartPriceGapItem;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link MakePartPriceGapItemMapper} 真实 DB CRUD 验证。 */
@Tag("integration")
@DisplayName("MakePartPriceGapItemMapper · lp_make_part_price_gap_item CRUD")
class MakePartPriceGapItemMapperTest extends BomMapperTestBase {

  private final String batchId = "make_gap_test_" + UUID.randomUUID();

  @Autowired private MakePartPriceGapItemMapper mapper;

  @BeforeAll
  static void initGapTable() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          """
          CREATE TABLE IF NOT EXISTS lp_make_part_price_gap_item (
            id BIGINT NOT NULL AUTO_INCREMENT,
            calc_batch_id VARCHAR(64) NOT NULL,
            pricing_month VARCHAR(7) DEFAULT NULL,
            generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            oa_no VARCHAR(64) DEFAULT NULL,
            business_unit_type VARCHAR(32) DEFAULT NULL,
            parent_material_no VARCHAR(64) NOT NULL,
            parent_material_name VARCHAR(180) DEFAULT NULL,
            child_material_no VARCHAR(64) NOT NULL DEFAULT '',
            child_material_name VARCHAR(180) DEFAULT NULL,
            child_material_spec VARCHAR(255) DEFAULT NULL,
            scrap_code VARCHAR(64) NOT NULL DEFAULT '',
            scrap_name VARCHAR(180) DEFAULT NULL,
            missing_price_role VARCHAR(16) NOT NULL,
            missing_material_no VARCHAR(64) NOT NULL DEFAULT '',
            missing_material_name VARCHAR(180) DEFAULT NULL,
            price_type VARCHAR(32) DEFAULT NULL,
            reason VARCHAR(500) DEFAULT NULL,
            oa_push_status VARCHAR(16) NOT NULL DEFAULT 'NOT_PUSHED',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_make_gap_batch_role (
              calc_batch_id,
              parent_material_no,
              child_material_no,
              scrap_code,
              missing_price_role
            )
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
    }
  }

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "DELETE FROM lp_make_part_price_gap_item WHERE calc_batch_id = '" + batchId + "'");
    }
  }

  @Test
  @DisplayName("insert/selectById：可保存制造件、原材料、废料和要补价料号")
  void insertAndSelectById() {
    MakePartPriceGapItem item = newGapItem("RAW", "301050066");
    item.setReason("原材料价缺失");

    int affected = mapper.insert(item);

    assertThat(affected).isEqualTo(1);
    assertThat(item.getId()).isNotNull().isPositive();
    MakePartPriceGapItem loaded = mapper.selectById(item.getId());
    assertThat(loaded).isNotNull();
    assertThat(loaded.getPricingMonth()).isEqualTo("2026-05");
    assertThat(loaded.getParentMaterialNo()).isEqualTo("203250582");
    assertThat(loaded.getChildMaterialNo()).isEqualTo("301050066");
    assertThat(loaded.getScrapCode()).isEqualTo("301990317");
    assertThat(loaded.getMissingPriceRole()).isEqualTo("RAW");
    assertThat(loaded.getMissingMaterialNo()).isEqualTo("301050066");
    assertThat(loaded.getOaPushStatus()).isEqualTo("NOT_PUSHED");
  }

  @Test
  @DisplayName("selectPage：可按批次和缺价类型筛选")
  void selectPageByBatchAndRole() {
    mapper.insert(newGapItem("RAW", "301050066"));
    mapper.insert(newGapItem("SCRAP", "301990317"));

    Page<MakePartPriceGapItem> page =
        mapper.selectPage(
            new Page<>(1, 10),
            Wrappers.lambdaQuery(MakePartPriceGapItem.class)
                .eq(MakePartPriceGapItem::getCalcBatchId, batchId)
                .eq(MakePartPriceGapItem::getMissingPriceRole, "SCRAP"));

    assertThat(page.getTotal()).isEqualTo(1);
    assertThat(page.getRecords().get(0).getMissingMaterialNo()).isEqualTo("301990317");
  }

  private MakePartPriceGapItem newGapItem(String role, String missingMaterialNo) {
    MakePartPriceGapItem item = new MakePartPriceGapItem();
    item.setCalcBatchId(batchId);
    item.setPricingMonth("2026-05");
    item.setGeneratedAt(LocalDateTime.of(2026, 5, 20, 10, 0));
    item.setOaNo("OA-GOLDEN-001");
    item.setBusinessUnitType("COMMERCIAL");
    item.setParentMaterialNo("203250582");
    item.setParentMaterialName("D接管");
    item.setChildMaterialNo("301050066");
    item.setChildMaterialName("TP2 Y2");
    item.setChildMaterialSpec("Φ23.5x1.3");
    item.setScrapCode("301990317");
    item.setScrapName("废紫铜");
    item.setMissingPriceRole(role);
    item.setMissingMaterialNo(missingMaterialNo);
    item.setMissingMaterialName("RAW".equals(role) ? "TP2 Y2" : "废紫铜");
    item.setPriceType("联动价");
    item.setReason(role + " 缺价");
    item.setOaPushStatus("NOT_PUSHED");
    return item;
  }
}
