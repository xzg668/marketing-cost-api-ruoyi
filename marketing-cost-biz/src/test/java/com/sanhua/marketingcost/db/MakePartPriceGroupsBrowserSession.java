package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MakePartPriceCalcQueryRequest;
import com.sanhua.marketingcost.service.pricing.MakePartPriceGroups;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Actual captured rows exercised in disposable MySQL; never writes to the source environment. */
@Tag("integration")
@EnabledIfSystemProperty(named="make.groups.fixture",matches=".+")
class MakePartPriceGroupsBrowserSession extends MonthlyRepriceCostingFixture {
  @Autowired MakePartPriceGroups groups;
  @Test void capturedRowsKeepPricesHistoryAndAssociationEvidence() throws Exception {
    var json=new ObjectMapper().findAndRegisterModules();
    var fixture=json.readTree(Files.readString(Path.of(System.getProperty("make.groups.fixture"))));
    for(var pair:Map.of("lp_bom_costing_row","organizations","lp_make_part_price_calc_row","rows").entrySet()) {
      var names=jdbc.queryForList("SHOW COLUMNS FROM `"+pair.getKey()+"`").stream().filter(r->!Objects.toString(r.get("Extra"),"").contains("GENERATED")).map(r->r.get("Field").toString()).collect(java.util.stream.Collectors.toSet());
      for(var row:fixture.required(pair.getValue())) {
        var columns=new ArrayList<String>();var values=new ArrayList<Object>();
        row.fields().forEachRemaining(f->{if(!names.contains(f.getKey()))return;columns.add("`"+f.getKey()+"`");values.add(f.getValue().isNull()?null:f.getValue().asText());});
        jdbc.update("INSERT INTO `"+pair.getKey()+"` ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.toArray());
      }
    }
    // Restore the existing production menu in this disposable fixture only.
    try(var connection=jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V100__make_part_price_calc_menu.sql"));
    }
    var before=jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row ORDER BY id");
    MakePartPriceGroupsIntegrationTest.auth("COMMERCIAL");
    var q=new MakePartPriceCalcQueryRequest();q.setParentMaterialNo("7218501041211");q.setPricingMonth("2026-11");
    var page=groups.page(q);assertThat(page.records().size()).isGreaterThanOrEqualTo(2);
    assertThat(page.records()).anySatisfy(g->{assertThat(g.row().getGrossWeightG()).isEqualByComparingTo("26");assertThat(g.quoteCount()).isGreaterThanOrEqualTo(3);assertThat(g.row().getParentTotalCostPrice()).isEqualByComparingTo("0.57415929");});
    assertThat(page.records()).anySatisfy(g->{assertThat(g.row().getGrossWeightG()).isEqualByComparingTo("24.8");assertThat(g.row().getParentTotalCostPrice()).isEqualByComparingTo("0.58281416");});
    var all=groups.page(new MakePartPriceCalcQueryRequest());assertThat(all.total()).isLessThan(before.size());
    String user="make_group_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'制造价格只读验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",user,new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,1 FROM sys_user WHERE user_name=?",user);
    var state=Path.of(System.getProperty("make.groups.state"));
    var data=new LinkedHashMap<String,Object>();data.put("username",user);data.put("password",password);data.put("jdbcUrl",MYSQL.getJdbcUrl());data.put("databaseUser",MYSQL.getUsername());data.put("databasePassword",MYSQL.getPassword());data.put("total",all.total());data.put("sample",page);data.put("originalRows",before.size());
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(state,json.writeValueAsString(data));
    var release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(45).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row ORDER BY id")).isEqualTo(before);
    Files.writeString(Path.of(state+".db-verified"),"{\"passed\":true}");
  }
}
