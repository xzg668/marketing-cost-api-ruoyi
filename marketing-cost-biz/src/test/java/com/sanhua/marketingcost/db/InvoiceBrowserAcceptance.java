package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.invoice.InvoiceWorkbookParserTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Fresh isolated database and real authenticated HTTP/UI; no pre-existing acceptance workflow is replayed. */
final class InvoiceBrowserAcceptance {
  static void await(JdbcTemplate jdbc,int port,JwtUtils jwt,String oaNo,long formId,long itemId,String material,String month,long oldCostId) {
    try {
      var state=Path.of(System.getProperty("invoice.browser.state"));var release=Path.of(state+".release");
      assertThat(Files.exists(state)).as("fresh session only").isFalse();assertThat(Files.exists(release)).isFalse();
      new ResourceDatabasePopulator(new ClassPathResource("db/V346__monthly_material_price_menu.sql"),new ClassPathResource("db/V355__invoice_price_menu.sql")).execute(jdbc.getDataSource());
      String name="invoice_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_time) VALUES(?,?,?,'COMMERCIAL','0','0','INV',NOW(),NOW())",name,"发票验收",new BCryptPasswordEncoder().encode(password));
      long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,name);
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,role_id FROM sys_role WHERE role_key='admin' AND del_flag='0'",user);
      var before=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",oldCostId);
      var rows=new String[][]{row(material,"BROWSER-2","ODS-S","80.38555556"),row(material,"BROWSER-3","ODS-S","85")};
      for(var row:rows)row[6]="件";
      var second=new String[][]{row(material,"BROWSER-4","ODS-S","80")};second[0][6]="件";
      Path firstFile=Path.of(state+".first.xlsx"),secondFile=Path.of(state+".second.xlsx");
      Files.write(firstFile,workbook("11月任意Sheet",rows));Files.write(secondFile,workbook("Sheet名字再次变化",second));
      var value=new LinkedHashMap<String,Object>();value.put("apiPort",port);value.put("frontendPort",OdsBrowserAcceptance.FRONTEND_PORT);
      value.put("username",name);value.put("password",password);value.put("token",jwt.generateToken(name,"COMMERCIAL"));
      value.put("oaNo",oaNo);value.put("formId",formId);value.put("itemId",itemId);value.put("material",material);value.put("month",month);
      value.put("oldCostId",oldCostId);value.put("firstFile",firstFile.toString());value.put("secondFile",secondFile.toString());
      try(var connection=jdbc.getDataSource().getConnection()){value.put("jdbcUrl",connection.getMetaData().getURL());}
      Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(state,new ObjectMapper().writeValueAsString(value));
      long deadline=System.nanoTime()+Duration.ofMinutes(45).toNanos();
      while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(250);
      assertThat(Files.exists(release)).as("real browser completed").isTrue();
      assertThat(new ObjectMapper().readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
      var after=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",oldCostId);
      // The existing cost workflow retires trial/current versions; monetary and source evidence stays immutable.
      for(String lifecycleField:List.of("status","updated_at")){before.remove(lifecycleField);after.remove(lifecycleField);}
      assertThat(after).isEqualTo(before);
      assertThat(jdbc.queryForList("SELECT unit_price FROM lp_material_monthly_price WHERE material_code=? ORDER BY id",java.math.BigDecimal.class,material))
          .usingComparatorForType(java.math.BigDecimal::compareTo,java.math.BigDecimal.class)
          .containsExactly(new java.math.BigDecimal("11.3"),new java.math.BigDecimal("85"),new java.math.BigDecimal("80"));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status IN ('SUCCESS','HISTORY','STALE')",Integer.class,itemId)).isGreaterThanOrEqualTo(3);
    } catch(Exception failure) {throw new AssertionError("Invoice browser acceptance failed",failure);}
  }
}
