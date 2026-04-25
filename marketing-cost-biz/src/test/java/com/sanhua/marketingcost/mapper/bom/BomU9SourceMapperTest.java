package com.sanhua.marketingcost.mapper.bom;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
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

/** {@link BomU9SourceMapper} 真实 DB CRUD 验证。 */
@Tag("integration")
@DisplayName("BomU9SourceMapper · lp_bom_u9_source CRUD")
class BomU9SourceMapperTest extends BomMapperTestBase {

  /** 每个测试独立批次，避免相互污染 */
  private final String batchId = "b_test_" + UUID.randomUUID();

  @Autowired private BomU9SourceMapper mapper;

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("DELETE FROM lp_bom_u9_source WHERE import_batch_id = '" + batchId + "'");
    }
  }

  @Test
  @DisplayName("insert：必填字段齐全时写入成功，id 自增回填")
  void testInsert() {
    BomU9Source row = newValidRow("P001", "C001", 1);

    int affected = mapper.insert(row);

    assertThat(affected).isEqualTo(1);
    assertThat(row.getId()).as("id 应由 AUTO_INCREMENT 回填").isNotNull().isPositive();
  }

  @Test
  @DisplayName("selectById：插后查出的字段全部对齐，包含 DATE / DECIMAL / TINYINT 三种特殊类型")
  void testSelectById() {
    BomU9Source row = newValidRow("P002", "C002", 2);
    row.setQtyPerParent(new BigDecimal("3.50000000"));
    row.setU9IsCostFlag(1);
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    mapper.insert(row);

    BomU9Source loaded = mapper.selectById(row.getId());

    assertThat(loaded).isNotNull();
    assertThat(loaded.getParentMaterialNo()).isEqualTo("P002");
    assertThat(loaded.getChildMaterialNo()).isEqualTo("C002");
    assertThat(loaded.getChildSeq()).isEqualTo(2);
    assertThat(loaded.getQtyPerParent()).isEqualByComparingTo(new BigDecimal("3.5"));
    assertThat(loaded.getU9IsCostFlag()).isEqualTo(1);
    assertThat(loaded.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(loaded.getEffectiveTo()).isEqualTo(LocalDate.of(9999, 12, 31));
  }

  @Test
  @DisplayName("updateById：改单字段后重新查出值已更新")
  void testUpdate() {
    BomU9Source row = newValidRow("P003", "C003", 3);
    row.setChildMaterialName("原名");
    mapper.insert(row);

    BomU9Source patch = new BomU9Source();
    patch.setId(row.getId());
    patch.setChildMaterialName("新名");
    int affected = mapper.updateById(patch);

    assertThat(affected).isEqualTo(1);
    assertThat(mapper.selectById(row.getId()).getChildMaterialName()).isEqualTo("新名");
  }

  @Test
  @DisplayName("deleteById：物理删除后 selectById 返回 null")
  void testDelete() {
    BomU9Source row = newValidRow("P004", "C004", 4);
    mapper.insert(row);
    Long id = row.getId();

    int affected = mapper.deleteById(id);

    assertThat(affected).isEqualTo(1);
    assertThat(mapper.selectById(id)).isNull();
  }

  // ============================ 辅助：构造合法样本 ============================

  private BomU9Source newValidRow(String parent, String child, int seq) {
    BomU9Source row = new BomU9Source();
    row.setImportBatchId(batchId);
    row.setSourceType("EXCEL");
    row.setImportedAt(LocalDateTime.now());
    row.setParentMaterialNo(parent);
    row.setChildMaterialNo(child);
    row.setChildSeq(seq);
    row.setBomPurpose("主制造");
    return row;
  }
}
