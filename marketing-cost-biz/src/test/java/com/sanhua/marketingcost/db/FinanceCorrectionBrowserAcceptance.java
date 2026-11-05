package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Disposable browser session. Imports and both actual quotation costings are performed through UI. */
final class FinanceCorrectionBrowserAcceptance {
  static void await(JdbcTemplate jdbc,int port,JwtUtils jwt,String oaNo,long form,long item,String material,String month) {
    try {
      Path state=Path.of(System.getProperty("finance.correction.browser.state")),release=Path.of(state+".release");
      assertThat(Files.exists(state)||Files.exists(release)).as("fresh acceptance session").isFalse();
      new ResourceDatabasePopulator(new ClassPathResource("db/V346__monthly_material_price_menu.sql")).execute(jdbc.getDataSource());
      String name="finance_corr_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_time) VALUES(?,?,?,'COMMERCIAL','0','0','FIN-CORR',NOW(),NOW())",name,"基价更正验收",new BCryptPasswordEncoder().encode(password));
      long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,name);
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,role_id FROM sys_role WHERE role_key='admin' AND del_flag='0'",user);
      var value=new LinkedHashMap<String,Object>();
      value.put("apiPort",port);value.put("frontendPort",OdsBrowserAcceptance.FRONTEND_PORT);
      value.put("username",name);value.put("password",password);value.put("token",jwt.generateToken(name,"COMMERCIAL"));
      value.put("oaNo",oaNo);value.put("formId",form);value.put("itemId",item);value.put("material",material);value.put("month",month);
      Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(state,new ObjectMapper().writeValueAsString(value));
      long deadline=System.nanoTime()+Duration.ofMinutes(45).toNanos();
      Map<String,Object> before=null;long firstCost=0;
      var first=Path.of(state+".first-cost.json");var captured=Path.of(state+".first-captured.json");
      while(!Files.exists(release)&&System.nanoTime()<deadline) {
        if(before==null && Files.exists(first)) {
          firstCost=new ObjectMapper().readTree(Files.readString(first)).path("costVersionId").asLong();
          before=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",firstCost);
          assertThat(((Number)before.get("oa_form_item_id")).longValue()).isEqualTo(item);
          Files.writeString(captured,"{\"captured\":true}");
        }
        Thread.sleep(250);
      }
      assertThat(Files.exists(release)).as("browser completed").isTrue();
      var evidence=new ObjectMapper().readTree(Files.readString(release));
      assertThat(evidence.path("passed").asBoolean()).isTrue();
      assertThat(before).isNotNull();
      assertThat(evidence.path("firstCostVersionId").asLong()).isEqualTo(firstCost);
      var after=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",firstCost);
      for(String field:List.of("status","updated_at")){before.remove(field);after.remove(field);}
      assertThat(after).as("original costing evidence remains exact").isEqualTo(before);
      var monthly=jdbc.queryForList("SELECT id,unit_price,superseded_by_finance_batch_id FROM lp_material_monthly_price WHERE material_code=? ORDER BY id",material);
      assertThat(monthly).hasSize(2);
      assertThat((BigDecimal)monthly.get(0).get("unit_price")).isEqualByComparingTo("10");
      assertThat((BigDecimal)monthly.get(1).get("unit_price")).isEqualByComparingTo("11");
      assertThat(monthly.get(0).get("superseded_by_finance_batch_id")).isNotNull();
      assertThat(monthly.get(1).get("superseded_by_finance_batch_id")).isNull();
      for(String key:List.of("firstCostVersionId","secondCostVersionId")) {
        long cost=evidence.path(key).asLong();assertThat(cost).isPositive();
        var version=jdbc.queryForMap("SELECT oa_form_item_id,pricing_month,status,jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=?",cost);
        assertThat(((Number)version.get("oa_form_item_id")).longValue()).isEqualTo(item);
        assertThat(version.get("pricing_month")).isEqualTo(month);
        assertThat(version.get("status")).isIn("SUCCESS","HISTORY","STALE");
        assertThat(version.get("jianji_snapshot_json").toString()).contains("monthlyPriceId");
        long expected=((Number)monthly.get(key.equals("firstCostVersionId")?0:1).get("id")).longValue();
        assertThat(jdbc.queryForList("SELECT monthly_price_id FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",Long.class,cost,material)).containsExactly(expected);
      }
      assertThat(evidence.path("firstCostVersionId").asLong()).isNotEqualTo(evidence.path("secondCostVersionId").asLong());
    } catch(Exception failure){throw new AssertionError("Finance correction browser acceptance failed",failure);}
  }
}
