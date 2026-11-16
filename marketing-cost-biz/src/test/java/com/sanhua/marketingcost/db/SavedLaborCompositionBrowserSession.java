package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.costing.CostAmountCompositionService;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Read-only projection regression against a captured saved version in a disposable MySQL. */
@Tag("integration")
@EnabledIfSystemProperty(named="labor.composition.fixture",matches=".+")
class SavedLaborCompositionBrowserSession extends MonthlyRepriceCostingFixture {
  @Autowired CostAmountCompositionService composition;
  @Test void savedLaborExclusionsAreVisibleWithoutRewritingOrReadingCurrentCms() throws Exception {
    var json=new ObjectMapper().findAndRegisterModules();
    var fixture=json.readTree(Files.readString(Path.of(System.getProperty("labor.composition.fixture"))));
    // Only tables consumed by the saved fee reader; part/preparation writes are outside this regression.
    for(String table:List.of("oa_form","oa_form_item","lp_material_monthly_price","lp_quote_cost_run_version","lp_cost_run_cost_item")) {
      var columns=jdbc.queryForList("SHOW COLUMNS FROM `"+table+"`").stream().map(row->row.get("Field").toString()).collect(java.util.stream.Collectors.toSet());
      for(var row:fixture.required(table)) {
        var names=new ArrayList<String>();var values=new ArrayList<Object>();
        row.fields().forEachRemaining(field->{
          assertThat(columns).as(table+" schema").contains(field.getKey());
          names.add("`"+field.getKey()+"`");values.add(field.getValue().isNull()?null:field.getValue().asText());
        });
        jdbc.update("INSERT INTO `"+table+"` ("+String.join(",",names)+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.toArray());
      }
    }
    var version=fixture.required("lp_quote_cost_run_version").get(0);long id=version.required("id").asLong();
    String run=version.required("cost_run_no").asText();
    var before=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",id);
    var costs=jdbc.queryForList("SELECT * FROM lp_cost_run_cost_item WHERE cost_run_version_id=? ORDER BY id",id);
    var auth=new UsernamePasswordAuthenticationToken("saved-labor-test","unused",List.of(new SimpleGrantedAuthority("ingest:quote:list")));
    auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));SecurityContextHolder.getContext().setAuthentication(auth);
    var views=new LinkedHashMap<String,Object>();
    try {
      for(var cost:costs) {
        String code=cost.get("cost_code").toString();
        if(!Set.of("DIRECT_LABOR","INDIRECT_LABOR","AUX_0202","WATER_POWER").contains(code))continue;
        var result=composition.read(run,((Number)cost.get("id")).longValue(),null);views.put(code,result);
        assertThat(result.composition().origin()).isEqualTo("SAVED");
        if(code.equals("DIRECT_LABOR")) {
          assertThat(result.amount()).isEqualTo("4.038961");assertThat(result.referenceAmount()).isEqualTo("4.038961");
          assertThat(result.difference()).isEqualTo("0.000000");
          assertThat(result.calculation()).contains("4.601193","0.562232","4.038961");
        }
        if(code.equals("INDIRECT_LABOR"))assertThat(result.referenceAmount()).isEqualTo("0.091700");
        if(code.equals("AUX_0202"))assertThat(result.referenceAmount()).isEqualTo("0.077818");
        if(code.equals("WATER_POWER"))assertThat(result.calculation()).contains("4.038961","0.091700","4.130661");
      }
    } finally {SecurityContextHolder.clearContext();}
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",id)).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM lp_cost_run_cost_item WHERE cost_run_version_id=? ORDER BY id",id)).isEqualTo(costs);
    String user="saved_labor_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'工资底稿隔离验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",user,new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,1 FROM sys_user WHERE user_name=?",user);
    var data=new LinkedHashMap<String,Object>();data.put("username",user);data.put("password",password);
    data.put("jdbcUrl",MYSQL.getJdbcUrl());data.put("databaseUser",MYSQL.getUsername());data.put("databasePassword",MYSQL.getPassword());
    data.put("versionId",id);data.put("costRunNo",run);data.put("compositions",views);
    var state=Path.of(System.getProperty("labor.composition.state"));
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(data));
    var release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",id)).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM lp_cost_run_cost_item WHERE cost_run_version_id=? ORDER BY id",id)).isEqualTo(costs);
    Files.writeString(Path.of(state+".db-verified"),"{\"passed\":true}");
  }
}
