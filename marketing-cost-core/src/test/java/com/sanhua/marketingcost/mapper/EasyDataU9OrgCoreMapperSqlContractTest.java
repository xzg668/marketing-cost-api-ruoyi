package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import java.lang.reflect.Method;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EasyData U9 组织字段 core Mapper 契约")
class EasyDataU9OrgCoreMapperSqlContractTest {

  @Test
  @DisplayName("三张 BOM 实体均暴露 priceOrgCode 字段")
  void entitiesExposePriceOrgCode() throws Exception {
    assertThat(BomU9Source.class.getDeclaredField("priceOrgCode").getType()).isEqualTo(String.class);
    assertThat(BomRawHierarchy.class.getDeclaredField("priceOrgCode").getType()).isEqualTo(String.class);
    assertThat(U9BomByproductMaster.class.getDeclaredField("priceOrgCode").getType())
        .isEqualTo(String.class);
  }

  @Test
  @DisplayName("BomU9Source batchUpsert 写入 price_org_code 且不默认商用")
  void bomU9SourceBatchUpsertCarriesOrg() throws Exception {
    String sql = insertSql(BomU9SourceMapper.class.getMethod("batchUpsert", List.class));

    assertThat(sql)
        .contains(
            "INSERT INTO lp_bom_u9_source",
            "price_org_code",
            "NULLIF(TRIM(#{e.priceOrgCode}), '')",
            "ON DUPLICATE KEY UPDATE")
        .doesNotContain("COALESCE(NULLIF(TRIM(#{e.priceOrgCode}), ''), '210')")
        .doesNotContain("price_org_code = VALUES(price_org_code)");
  }

  @Test
  @DisplayName("BomRawHierarchy batchUpsert 写入 price_org_code 且不默认商用")
  void bomRawHierarchyBatchUpsertCarriesOrg() throws Exception {
    String sql = insertSql(BomRawHierarchyMapper.class.getMethod("batchUpsert", List.class));

    assertThat(sql)
        .contains(
            "INSERT INTO lp_bom_raw_hierarchy",
            "price_org_code",
            "NULLIF(TRIM(#{e.priceOrgCode}), '')",
            "ON DUPLICATE KEY UPDATE")
        .doesNotContain("COALESCE(NULLIF(TRIM(#{e.priceOrgCode}), ''), '210')")
        .doesNotContain("price_org_code = VALUES(price_org_code)");
  }

  @Test
  @DisplayName("core U9BomByproductMaster upsert 写入 price_org_code 且不覆盖组织")
  void u9BomByproductUpsertCarriesOrg() throws Exception {
    String sql = insertSql(
        U9BomByproductMasterMapper.class.getMethod("upsert", U9BomByproductMaster.class));

    assertThat(sql)
        .contains(
            "INSERT INTO lp_u9_bom_byproduct_master",
            "price_org_code",
            "NULLIF(TRIM(#{priceOrgCode}), '')",
            "ON DUPLICATE KEY UPDATE")
        .doesNotContain("COALESCE(NULLIF(TRIM(#{priceOrgCode}), ''), '210')")
        .doesNotContain("price_org_code = VALUES(price_org_code)");
  }

  private static String insertSql(Method method) {
    Insert insert = method.getAnnotation(Insert.class);
    assertThat(insert).isNotNull();
    return String.join("\n", insert.value());
  }
}
