package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Local disposable database: original finance approval, cost, actual ratio import and recost. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15238",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class SupplierHighestRepriceBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;

  @org.springframework.test.context.DynamicPropertySource
  static void period(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state = Path.of(System.getProperty("supplier.highest.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    try (var connection = jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V90__supplier_supply_ratio_menu.sql"));
    }
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(1).atTime(12, 0);
    String product = "SHR-" + UUID.randomUUID().toString().substring(0, 8), material = product + "-PART";
    String packaging = product + "-ZERO-CHILD";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')", product);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type,business_type,shipping_fee) VALUES(?,?,?,'COMMERCIAL','批量品',0)", form, product, "最高价审核与供货比率导入验收");
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    new OrdinaryCostingTestSources(jdbc, hierarchy).ordinarySources(product, material, form, date);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0", product);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET qty_per_parent=1,qty_per_top=1 WHERE top_product_code=? AND material_code IN (?,?)", product, product+"-ZERO-PACK", packaging);
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?", product+"-BARE", product);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')", product+"-BARE", product+"-BARE", product);
    jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?", product+"-BARE", product);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)", date.getYear());
    for (String code : List.of(material, packaging))
      for (String supplier : List.of("A", "B"))
        jdbc.update("INSERT INTO lp_price_fixed_item(org_code,supplier_code,supplier_name,material_code,material_name,unit,fixed_price,tax_included,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('210',?,?,?,?,'件',?,0,?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",
            supplier, "供应商"+supplier, code, code, new BigDecimal(supplier.equals("A") ? "10" : "30"), date.toLocalDate());
    var originalPrices = jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id");
    String actor = "highest_"+UUID.randomUUID().toString().substring(0, 8), password = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'最高价审核验收',?,'COMMERCIAL','0','0','SHR_TEST',NOW(),'SHR_TEST',NOW())", actor, new BCryptPasswordEncoder().encode(password));
    long user = jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)", user);
    var json = new ObjectMapper().findAndRegisterModules();
    Long legacySnapshot = Boolean.getBoolean("supplier.highest.browser.legacy-bom")
        ? legacyMonthlyCard(product,material,form,item,month,json) : null;
    var legacyDetails = legacySnapshot == null ? List.of() : jdbc.queryForList(
        "SELECT * FROM lp_quote_bom_monthly_snapshot_detail WHERE monthly_snapshot_id=? ORDER BY line_no", legacySnapshot);
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(Map.of("apiPort",port,"username",actor,"password",password,
        "month",month,"oaNo",product,"itemId",item,"material",material,"packaging",packaging,
        "materialQuantity",legacySnapshot==null?"2.5":"9")));
    Path release = Path.of(state+".release");
    long deadline = System.nanoTime()+Duration.ofMinutes(20).toNanos();
    while (!Files.exists(release) && System.nanoTime()<deadline) Thread.sleep(200);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    var versions = jdbc.queryForList("SELECT id,status,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status IN ('SUCCESS','HISTORY') ORDER BY id", item);
    assertThat(versions).hasSize(2);
    assertThat((BigDecimal)versions.getLast().get("total_cost")).isLessThan((BigDecimal)versions.getFirst().get("total_cost"));
    for (String code : List.of(material,packaging)) {
      var prices = jdbc.queryForList("SELECT id,unit_price,disabled FROM lp_material_monthly_price WHERE material_code=? ORDER BY id", code);
      assertThat(prices).hasSize(2);
      assertThat((BigDecimal)prices.getFirst().get("unit_price")).isEqualByComparingTo("30");
      assertThat(((Number)prices.getFirst().get("disabled")).intValue()).isEqualTo(1);
      assertThat((BigDecimal)prices.getLast().get("unit_price")).isEqualByComparingTo("10");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_supplier_price_decision WHERE material_code=? AND decision='FALLBACK_HIGH'", Integer.class, code))
          .isEqualTo(Boolean.getBoolean("supplier.highest.browser.inline-rule") ? 0 : 1);
    }
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id")).isEqualTo(originalPrices);
    if (legacySnapshot != null) {
      assertThat(jdbc.queryForObject("SELECT active_flag FROM lp_quote_bom_monthly_snapshot WHERE id=?",Integer.class,legacySnapshot)).isEqualTo(1);
      assertThat(jdbc.queryForList("SELECT * FROM lp_quote_bom_monthly_snapshot_detail WHERE monthly_snapshot_id=? ORDER BY line_no",legacySnapshot)).isEqualTo(legacyDetails);
      var active = jdbc.queryForMap("SELECT id,bom_version,bom_batch_id FROM lp_quote_bom_monthly_snapshot WHERE product_code=? AND active_flag=1",product);
      assertThat(((Number)active.get("id")).longValue()).isEqualTo(legacySnapshot);
      assertThat(active.get("bom_version")).isEqualTo("F006");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot WHERE product_code=?",Integer.class,product)).isEqualTo(1);
      assertThat(jdbc.queryForList("SELECT DISTINCT qty FROM lp_cost_run_part_item WHERE oa_form_item_id=? AND part_code=?",BigDecimal.class,item,material)).allSatisfy(quantity -> assertThat(quantity).isEqualByComparingTo("9"));
    }
    Files.writeString(Path.of(state+".db-verified"), json.writeValueAsString(Map.of("passed",true,"versions",versions,
        "ordinaryAndPackagingHighestThenActual",true,"originalSourcePricesPreserved",true,"remoteAccess",false)));
  }

  private long legacyMonthlyCard(String product,String material,long form,long item,String month,ObjectMapper json) throws Exception {
    var effective = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingDate().minusDays(1);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET bom_version='F007',effective_from=?,child_type=NULL WHERE top_product_code=?",effective,product);
    jdbc.update("""
        INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,
          parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,
          child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type,bom_status,
          shape_attr,is_virtual,material_category_1,material_category_2,u9_is_cost_flag)
        SELECT r.source_import_batch_id,'U9',NOW(),r.price_org_code,r.parent_code,r.material_code,r.material_name,
          '件',r.bom_purpose,r.bom_version,r.sort_seq,r.qty_per_parent,1,r.effective_from,r.effective_to,'标准','已核准',
          r.shape_attr,IF(r.shape_attr='虚拟',1,0),m.main_category_code,m.main_category_name,1
        FROM lp_bom_raw_hierarchy r JOIN lp_material_master_raw m
          ON m.material_code=r.material_code AND m.organization_code='COMMERCIAL'
        WHERE r.top_product_code=? AND r.level>0
        """,product);
    var scope = new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(form,item,product,month,
        "COMMERCIAL",product,product,"","","210","COMMERCIAL",effective.plusDays(1),effective.plusDays(1).atStartOfDay());
    String identity = com.sanhua.marketingcost.service.quotebom.U9MonthlySnapshotIdentity.from(scope).identityKey();
    var rows = hierarchy.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
        .<com.sanhua.marketingcost.entity.BomRawHierarchy>lambdaQuery()
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getTopProductCode,product)
        .orderByAsc(com.sanhua.marketingcost.entity.BomRawHierarchy::getLevel));
    jdbc.update("""
        INSERT INTO lp_quote_bom_monthly_snapshot(product_code,price_org_code,business_unit_type,
          material_organization_code,snapshot_identity_key,customer_code,package_method,cost_period_month,
          bom_source,bom_purpose,bom_version,bom_batch_id,sync_type,sync_status,sync_by,active_flag,line_count)
        VALUES(?,'210','COMMERCIAL','COMMERCIAL',?,'','',?,'U9','主制造','F006','OLD-BOM','AUTO','SUCCESS','TEST',1,?)
        """,product,identity,month,rows.size());
    long id=jdbc.queryForObject("SELECT id FROM lp_quote_bom_monthly_snapshot WHERE snapshot_identity_key=?",Long.class,identity);
    int line=0;
    for (var row : rows) {
      row.setBomVersion("F006");row.setBuildBatchId("OLD-BOM");row.setChildType(null);
      if (material.equals(row.getMaterialCode())) { row.setQtyPerParent(new BigDecimal("9"));row.setQtyPerTop(new BigDecimal("9")); }
      jdbc.update("INSERT INTO lp_quote_bom_monthly_snapshot_detail(monthly_snapshot_id,line_no,raw_node_json) VALUES(?,?,?)",id,line++,json.writeValueAsString(row));
    }
    return id;
  }
}
