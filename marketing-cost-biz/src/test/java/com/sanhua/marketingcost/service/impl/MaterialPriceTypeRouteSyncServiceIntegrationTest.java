package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService;
import com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService.RouteCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@DisplayName("T3 正式价格自动维护当前价格类型")
class MaterialPriceTypeRouteSyncServiceIntegrationTest extends BomMapperTestBase {

  private static final String MATERIAL = "T3-ROUTE-INTEGRATION";

  @Autowired private MaterialPriceTypeRouteSyncService service;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM lp_material_price_type WHERE material_code=?", MATERIAL);
  }

  @Test
  void sameTypeIsIdempotentTypeChangeAppendsAndBusinessUnitsAreIsolated() {
    service.sync(command("COMMERCIAL", "PURCHASE_FIXED"));
    service.sync(command("COMMERCIAL", "FIXED"));
    service.sync(command("COMMERCIAL", "LINKED"));
    service.sync(command("HOUSEHOLD", "RANGE"));

    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_material_price_type WHERE material_code=?",
        Integer.class, MATERIAL)).isEqualTo(3);
    assertThat(jdbc.queryForList("""
        SELECT price_type FROM lp_material_price_type
        WHERE material_code=? AND business_unit_type='COMMERCIAL'
        ORDER BY created_at,id
        """, String.class, MATERIAL)).containsExactly("固定价", "联动价");
    assertThat(jdbc.queryForList("""
        SELECT price_type FROM lp_material_price_type
        WHERE material_code=? AND business_unit_type='HOUSEHOLD'
        ORDER BY created_at,id
        """, String.class, MATERIAL)).containsExactly("区间价");
  }

  private RouteCommand command(String businessUnitType, String priceType) {
    return new RouteCommand(
        MATERIAL,
        "集成测试物料",
        "规格",
        "件",
        businessUnitType,
        priceType,
        "integration_test",
        "TEST");
  }
}
