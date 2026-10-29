package com.sanhua.marketingcost.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Explicit opt-in handoff to browser QA; only this run's disposable MySQL is exposed. */
final class MonthlyCostComparisonBrowserSession {
  static void await(JdbcTemplate jdbc,int apiPort,String oa,long item,String month,String next,String b,String c,String d,long original,long repriced) {
    String location=System.getProperty("mrp09.browser.state",System.getProperty("mrp08.browser.state"));
    if (location == null) return;
    try {
      var target=Path.of(location); var release=Path.of(location+".release");
      if (Files.exists(target) || Files.exists(release)) throw new IllegalStateException("Browser handoff already exists");
      new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
          new org.springframework.core.io.ClassPathResource("db/V128__monthly_reprice_menu_seed.sql"),
          new org.springframework.core.io.ClassPathResource("db/V351__monthly_reprice_1130_menu.sql")).execute(jdbc.getDataSource());
      jdbc.update("INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms) VALUES(206,'报价单接入',200,2,'quote-requests','ingest/quote-requests/index','C','0','0','ingest:quote:list'),(907001,'MRP08报价执行',206,1,'','','F','0','0','ingest:quote:cost-run:execute')");
      jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES(10,206),(11,206),(10,907001),(11,907001)");
      String username="mrp08_quote_"+UUID.randomUUID().toString().substring(0,8);
      String password=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,?,'COMMERCIAL','0','0','MRP06',NOW(),'MRP06',NOW())",
          username,"MRP06 browser",new BCryptPasswordEncoder().encode(password));
      long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,11)",user);
      String director="mrp08_director_"+UUID.randomUUID().toString().substring(0,8);
      String directorPassword=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,?,'COMMERCIAL','0','0','MRP08',NOW(),'MRP08',NOW())",
          director,"MRP08 director",new BCryptPasswordEncoder().encode(directorPassword));
      long directorId=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,director);
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,10)",directorId);
      String household="mrp08_household_"+UUID.randomUUID().toString().substring(0,8), householdPassword=UUID.randomUUID().toString();
      String noAccess="mrp08_no_access_"+UUID.randomUUID().toString().substring(0,8), noAccessPassword=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'MRP08 household',?,'HOUSEHOLD','0','0','MRP08',NOW(),'MRP08',NOW()),(?,'MRP08 denied',?,'COMMERCIAL','0','0','MRP08',NOW(),'MRP08',NOW())",
          household,new BCryptPasswordEncoder().encode(householdPassword),noAccess,new BCryptPasswordEncoder().encode(noAccessPassword));
      long householdId=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,household);
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,11)",householdId);
      var state=new LinkedHashMap<String,Object>();
      state.put("household",household);state.put("householdPassword",householdPassword);
      state.put("noAccess",noAccess);state.put("noAccessPassword",noAccessPassword);
      state.put("apiPort",apiPort); state.put("frontendPort",OdsBrowserAcceptance.FRONTEND_PORT);
      state.put("username",username); state.put("password",password);
      state.put("director",director);state.put("directorPassword",directorPassword);
      state.put("oaNo",oa);state.put("itemId",item);state.put("month",month);
      state.put("originalCostVersionId",original);state.put("nextMonth",next);state.put("repriceCostVersionId",repriced);state.put("partB",b);state.put("partC",c);state.put("partD",d);
      try(var connection=jdbc.getDataSource().getConnection()) { state.put("jdbcUrl",connection.getMetaData().getURL()); }
      Files.createFile(target,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(target,new ObjectMapper().writeValueAsString(state));
      long deadline=System.nanoTime()+Duration.ofMinutes(25).toNanos();
      while (!Files.exists(release) && System.nanoTime()<deadline) Thread.sleep(250);
      if (!Files.exists(release)) throw new AssertionError("Browser acceptance did not release this disposable runtime");
      var result=new ObjectMapper().readTree(Files.readString(release));
      if (!result.path("passed").asBoolean()) throw new AssertionError("Browser acceptance failed; inspect stage evidence");
    } catch(Exception failure) { throw new AssertionError("MRP06 disposable browser handoff failed",failure); }
  }
}
