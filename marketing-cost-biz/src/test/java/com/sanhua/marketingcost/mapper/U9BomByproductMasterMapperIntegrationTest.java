package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("integration")
@DisplayName("U9BomByproductMasterMapper · 组织字段真实 DB 验证")
class U9BomByproductMasterMapperIntegrationTest extends BomMapperTestBase {

  private final String parent = "BP_" + UUID.randomUUID().toString().substring(0, 8);

  @Autowired private U9BomByproductMasterMapper mapper;

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "DELETE FROM lp_u9_bom_byproduct_master WHERE parent_material_no = '" + parent + "'");
    }
  }

  @Test
  @DisplayName("insert：显式商用组织可正确持久化")
  void insertPersistsExplicitCommercialOrganization() throws Exception {
    U9BomByproductMaster row = newRow("C_DEFAULT", "商用副产", "210");

    int affected = mapper.insert(row);

    assertThat(affected).isEqualTo(1);
    assertThat(loadOrg("C_DEFAULT")).isEqualTo("210");
  }

  @Test
  @DisplayName("upsert：显式商用组织可正确持久化")
  void upsertPersistsExplicitCommercialOrganization() throws Exception {
    U9BomByproductMaster row = newRow("C_BLANK", "商用组织副产", "210");

    int affected = mapper.upsert(row);

    assertThat(affected).isEqualTo(1);
    assertThat(loadOrg("C_BLANK")).isEqualTo("210");
  }

  @Test
  @DisplayName("upsert：同自然键按 price_org_code 隔离，更新商用不覆盖板换")
  void upsertSeparatesOrganizations() throws Exception {
    U9BomByproductMaster commercial = newRow("C_ORG", "商用副产", "210");
    U9BomByproductMaster plate = newRow("C_ORG", "板换副产", "220");

    mapper.upsert(commercial);
    mapper.upsert(plate);

    commercial.setByproductMaterialName("商用副产更新");
    mapper.upsert(commercial);

    assertThat(countRows("C_ORG")).isEqualTo(2);
    assertThat(loadByproductName("210", "C_ORG")).isEqualTo("商用副产更新");
    assertThat(loadByproductName("220", "C_ORG")).isEqualTo("板换副产");
  }

  private U9BomByproductMaster newRow(String byproductCode, String byproductName, String priceOrgCode) {
    U9BomByproductMaster row = new U9BomByproductMaster();
    row.setPriceOrgCode(priceOrgCode);
    row.setParentMaterialNo(parent);
    row.setParentMaterialName("测试母件");
    row.setBomPurpose("主制造");
    row.setByproductMaterialNo(byproductCode);
    row.setByproductMaterialName(byproductName);
    row.setOutputQty(new BigDecimal("1.00000000"));
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    row.setSourceType("EXCEL");
    row.setImportedAt(LocalDateTime.now());
    return row;
  }

  private String loadOrg(String byproductCode) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT price_org_code FROM lp_u9_bom_byproduct_master"
                + " WHERE parent_material_no = '" + parent + "'"
                + " AND byproduct_material_no = '" + byproductCode + "'")) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }

  private long countRows(String byproductCode) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) FROM lp_u9_bom_byproduct_master"
                + " WHERE parent_material_no = '" + parent + "'"
                + " AND byproduct_material_no = '" + byproductCode + "'")) {
      assertThat(rs.next()).isTrue();
      return rs.getLong(1);
    }
  }

  private String loadByproductName(String priceOrgCode, String byproductCode) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT byproduct_material_name FROM lp_u9_bom_byproduct_master"
                + " WHERE parent_material_no = '" + parent + "'"
                + " AND price_org_code = '" + priceOrgCode + "'"
                + " AND byproduct_material_no = '" + byproductCode + "'")) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }
}
