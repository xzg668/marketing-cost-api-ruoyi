package com.sanhua.marketingcost.mapper.bom;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link BomCostingRowMapper} 真实 DB CRUD 验证 + createdAt/updatedAt 自动填充。 */
@Tag("integration")
@DisplayName("BomCostingRowMapper · lp_bom_costing_row CRUD")
class BomCostingRowMapperTest extends BomMapperTestBase {

  private final String oaNo = "OA_TEST_" + UUID.randomUUID().toString().substring(0, 8);
  private final String buildBatchId = "f_test_" + UUID.randomUUID();

  @Autowired private BomCostingRowMapper mapper;

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("DELETE FROM lp_bom_costing_row WHERE oa_no = '" + oaNo + "'");
    }
  }

  @Test
  @DisplayName("insert：写入后 id 回填，createdAt / updatedAt 由 MetaObjectHandler 自动填上")
  void testInsert() {
    BomCostingRow row = newCostingRow("MAT001");

    int affected = mapper.insert(row);

    assertThat(affected).isEqualTo(1);
    assertThat(row.getId()).isNotNull().isPositive();
    // 自动填充生效验证（MetaObjectHandlerConfig.insertFill）
    BomCostingRow loaded = mapper.selectById(row.getId());
    assertThat(loaded.getCreatedAt()).as("createdAt 应由 MetaObjectHandler 自动填充").isNotNull();
    assertThat(loaded.getUpdatedAt()).as("updatedAt 应由 MetaObjectHandler 自动填充").isNotNull();
  }

  @Test
  @DisplayName("selectById：版本锁定字段 as_of_date / raw_version_effective_from 正确持久化")
  void testSelectById() {
    BomCostingRow row = newCostingRow("MAT002");
    row.setAsOfDate(LocalDate.of(2026, 4, 23));
    row.setRawVersionEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setSubtreeCostRequired(1);
    mapper.insert(row);

    BomCostingRow loaded = mapper.selectById(row.getId());

    assertThat(loaded.getOaNo()).isEqualTo(oaNo);
    assertThat(loaded.getMaterialCode()).isEqualTo("MAT002");
    assertThat(loaded.getAsOfDate()).isEqualTo(LocalDate.of(2026, 4, 23));
    assertThat(loaded.getRawVersionEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(loaded.getSubtreeCostRequired()).isEqualTo(1);
    assertThat(loaded.getIsCostingRow()).as("默认值 1").isEqualTo(1);
  }

  @Test
  @DisplayName("updateById：改 subtreeCostRequired，updatedAt 自动刷新")
  void testUpdate() throws Exception {
    BomCostingRow row = newCostingRow("MAT003");
    row.setSubtreeCostRequired(0);
    mapper.insert(row);
    LocalDateTime updatedBefore = mapper.selectById(row.getId()).getUpdatedAt();

    // 隔一毫秒确保 DATETIME 精度下 updatedAt 能观察到变化
    Thread.sleep(1100);
    BomCostingRow patch = new BomCostingRow();
    patch.setId(row.getId());
    patch.setSubtreeCostRequired(1);
    int affected = mapper.updateById(patch);

    assertThat(affected).isEqualTo(1);
    BomCostingRow reloaded = mapper.selectById(row.getId());
    assertThat(reloaded.getSubtreeCostRequired()).isEqualTo(1);
    assertThat(reloaded.getUpdatedAt())
        .as("MetaObjectHandler.updateFill 应刷新 updatedAt")
        .isAfter(updatedBefore);
  }

  @Test
  @DisplayName("deleteById：物理删除后 selectById 返回 null")
  void testDelete() {
    BomCostingRow row = newCostingRow("MAT004");
    mapper.insert(row);

    assertThat(mapper.deleteById(row.getId())).isEqualTo(1);
    assertThat(mapper.selectById(row.getId())).isNull();
  }

  // ============================ 辅助 ============================

  private BomCostingRow newCostingRow(String materialCode) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo(oaNo);
    row.setTopProductCode("TOP_" + materialCode);
    row.setMaterialCode(materialCode);
    row.setLevel(1);
    row.setPath("/TOP_" + materialCode + "/" + materialCode + "/");
    row.setQtyPerTop(new BigDecimal("2.00000000"));
    row.setBuildBatchId(buildBatchId);
    row.setBuiltAt(LocalDateTime.now());
    row.setAsOfDate(LocalDate.of(2026, 4, 23));
    row.setRawVersionEffectiveFrom(LocalDate.of(2026, 1, 1));
    return row;
  }
}
