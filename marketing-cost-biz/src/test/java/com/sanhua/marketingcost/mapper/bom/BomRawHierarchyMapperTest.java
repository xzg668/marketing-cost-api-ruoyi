package com.sanhua.marketingcost.mapper.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
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
import org.springframework.dao.DuplicateKeyException;

/** {@link BomRawHierarchyMapper} 真实 DB CRUD + UK 冲突验证。 */
@Tag("integration")
@DisplayName("BomRawHierarchyMapper · lp_bom_raw_hierarchy CRUD + uk_node")
class BomRawHierarchyMapperTest extends BomMapperTestBase {

  private final String buildBatchId = "h_test_" + UUID.randomUUID();
  private final String topProductCode = "TOP_" + UUID.randomUUID().toString().substring(0, 8);

  @Autowired private BomRawHierarchyMapper mapper;

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "DELETE FROM lp_bom_raw_hierarchy WHERE top_product_code = '" + topProductCode + "'");
    }
  }

  @Test
  @DisplayName("insert：顶层节点写入成功，id 回填")
  void testInsert() {
    BomRawHierarchy row = newTopNode();

    int affected = mapper.insert(row);

    assertThat(affected).isEqualTo(1);
    assertThat(row.getId()).isNotNull().isPositive();
  }

  @Test
  @DisplayName("selectById：查出的层级 / path / qty_per_top 与插入值一致")
  void testSelectById() {
    BomRawHierarchy row = newTopNode();
    row.setQtyPerTop(new BigDecimal("1.00000000"));
    mapper.insert(row);

    BomRawHierarchy loaded = mapper.selectById(row.getId());

    assertThat(loaded).isNotNull();
    assertThat(loaded.getTopProductCode()).isEqualTo(topProductCode);
    assertThat(loaded.getLevel()).isZero();
    assertThat(loaded.getPath()).isEqualTo("/" + topProductCode + "/");
    assertThat(loaded.getSourceType()).isEqualTo("U9");
    assertThat(loaded.getQtyPerTop()).isEqualByComparingTo(new BigDecimal("1"));
  }

  @Test
  @DisplayName("updateById：改 is_leaf 后重新查出值已更新")
  void testUpdate() {
    BomRawHierarchy row = newTopNode();
    row.setIsLeaf(0);
    mapper.insert(row);

    BomRawHierarchy patch = new BomRawHierarchy();
    patch.setId(row.getId());
    patch.setIsLeaf(1);
    int affected = mapper.updateById(patch);

    assertThat(affected).isEqualTo(1);
    assertThat(mapper.selectById(row.getId()).getIsLeaf()).isEqualTo(1);
  }

  @Test
  @DisplayName("deleteById：物理删除后 selectById 返回 null")
  void testDelete() {
    BomRawHierarchy row = newTopNode();
    mapper.insert(row);

    assertThat(mapper.deleteById(row.getId())).isEqualTo(1);
    assertThat(mapper.selectById(row.getId())).isNull();
  }

  @Test
  @DisplayName("uk_node：同 top + source_type + bom_purpose + effective_from + material + parent 不允许重复")
  void testUkConflict() {
    BomRawHierarchy first = newTopNode();
    mapper.insert(first);

    BomRawHierarchy dup = newTopNode();
    // 所有 UK 列完全一致（含 bom_purpose / effective_from），仅变业务属性 —— 应触发唯一键冲突
    dup.setMaterialName("应被拦截");

    assertThatThrownBy(() -> mapper.insert(dup))
        .as("uk_node 应捕获重复插入")
        .isInstanceOf(DuplicateKeyException.class);
  }

  // ============================ 辅助 ============================

  private BomRawHierarchy newTopNode() {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setTopProductCode(topProductCode);
    row.setParentCode(topProductCode);
    row.setMaterialCode(topProductCode);
    row.setLevel(0);
    row.setPath("/" + topProductCode + "/");
    row.setBomPurpose("主制造");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    row.setSourceType("U9");
    row.setSourceImportBatchId(buildBatchId);
    row.setBuildBatchId(buildBatchId);
    row.setBuiltAt(LocalDateTime.now());
    return row;
  }
}
