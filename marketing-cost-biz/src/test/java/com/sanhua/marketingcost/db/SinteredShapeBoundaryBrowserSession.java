package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Disposable data for testing the previous released jars against the corrected jars. */
@Tag("integration")
@EnabledIfSystemProperty(named="sintered.browser.state",matches=".+")
class SinteredShapeBoundaryBrowserSession extends MonthlyRepriceCostingFixture {
  @Autowired BomSettlementRuleMapper rules;
  @Test void keepIsolatedFixtureForReleasedJarComparison() throws Exception {
    var state=Path.of(System.getProperty("sintered.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    new ResourceDatabasePopulator(new ClassPathResource("db/V367__sintered_base_processing_fee.sql"),
        new ClassPathResource("db/V381__quotation_order_material_shape.sql"),
        new ClassPathResource("db/V382__quotation_order_price_source.sql")).execute(jdbc.getDataSource());
    unit = "COMMERCIAL"; priceOrg = "210"; materialOrg = "COMMERCIAL";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    repriceProperties.setGoLiveMonths(Map.of(unit, month));
    var date = java.time.YearMonth.parse(month).atDay(1).atTime(12, 0);
    String product = "SINTERED-" + UUID.randomUUID().toString().substring(0, 8);
    String material = product + "-RAW", base = "201850113";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES(?,'COMMERCIAL',80000,'SIN-TEST')", product);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')", form, product, product);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)", date.getYear());
    ordinarySources(product, material, form, date);
    comparisonFixedPrice(material, "2", date);
    // Use the observed November fee in a controlled source; no production price/adoption is changed.
    comparisonFixedPrice(base, "2.876106194690265487", date);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,'烧结基座','件','COMMERCIAL',?,1,'15','制造件')", base, product);
    var raw = rawHierarchy.selectOne(Wrappers.<com.sanhua.marketingcost.entity.BomRawHierarchy>lambdaQuery()
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getBuildBatchId, product)
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getMaterialCode, material));
    var parent = new com.sanhua.marketingcost.entity.BomRawHierarchy();
    org.springframework.beans.BeanUtils.copyProperties(raw, parent);
    parent.setId(null); parent.setMaterialCode(base); parent.setMaterialName("烧结基座");
    parent.setShapeAttr("制造件"); parent.setIsLeaf(0); parent.setQtyPerParent(BigDecimal.ONE); parent.setQtyPerTop(BigDecimal.ONE);
    parent.setPath("/" + product + "/" + base + "/"); parent.setSourceLineKey("SINTERED-BASE"); rawHierarchy.insert(parent);
    raw.setPath(parent.getPath() + material + "/"); raw.setParentCode(base); raw.setLevel(2); rawHierarchy.updateById(raw);

    buildOriginalFixture(form, item, product, month, date, material, Map.of());
    var shape=MaterialShapeSwitchTestData.create(jdbc,rawHierarchy,rules);
    // Both the original manufacturing path and explicit shape changes must remain valid.
    jdbc.update("UPDATE lp_material_master_raw SET material_name='烧结基座' WHERE material_code=?",shape.parent());
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET material_name='烧结基座' WHERE material_code=? AND top_product_code=?",shape.parent(),shape.product());
    jdbc.update("UPDATE lp_bom_u9_source SET child_material_name='烧结基座' WHERE child_material_no=?",shape.parent());
    String user="sintered_browser_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'形态计费验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",user,new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,1 FROM sys_user WHERE user_name=?",user);
    var data=new LinkedHashMap<String,Object>();
    data.put("sample",Map.of("oaNo",product,"product",product,"itemId",item,"month",month,"parent",base,"raw",material));
    data.put("shape",shape);data.put("username",user);data.put("password",password);
    data.put("jdbcUrl",MYSQL.getJdbcUrl());data.put("databaseUser",MYSQL.getUsername());data.put("databasePassword",MYSQL.getPassword());
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    var json=new ObjectMapper().findAndRegisterModules();Files.writeString(state,json.writeValueAsString(data));
    var release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,item)).isGreaterThanOrEqualTo(2);
    Files.writeString(Path.of(state+".db-verified"),"{\"passed\":true}");
  }
}
