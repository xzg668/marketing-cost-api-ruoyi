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
final class MonthlyRepriceWorkflowBrowserSession {
  static void await(JdbcTemplate jdbc,int apiPort,String oa,long item,String month,long original) {
    String location=System.getProperty("mrp07.browser.state");
    if (location == null) return;
    try {
      var target=Path.of(location); var release=Path.of(location+".release");
      if (Files.exists(target) || Files.exists(release)) throw new IllegalStateException("Browser handoff already exists");
      new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
          new org.springframework.core.io.ClassPathResource("db/V128__monthly_reprice_menu_seed.sql"),
          new org.springframework.core.io.ClassPathResource("db/V351__monthly_reprice_1130_menu.sql")).execute(jdbc.getDataSource());
      jdbc.update("INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms) VALUES(206,'报价单接入',200,2,'quote-requests','ingest/quote-requests/index','C','0','0','ingest:quote:list'),(907001,'MRP07报价执行',206,1,'','','F','0','0','ingest:quote:cost-run:execute')");
      jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES(10,206),(11,206),(10,907001),(11,907001)");
      // Explicit source-menu and finance capabilities for this run's staff/director only.
      jdbc.update("INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms) VALUES(907010,'财务基价',400,10,'/rules/finance-cu-base','pages:FinanceCuBasePricePage','C','0','0','cost:finance-cu-base:query'),(907011,'供应商供货比例',300,10,'/base/supplier-relation/supply-ratio','pages:SupplierSupplyRatioPage','C','0','0','base:supplier-supply-ratio:list'),(907012,'供货比例导入',907011,1,'',NULL,'F','0','0','base:supplier-supply-ratio:import'),(907013,'SRM固定价',400,11,'/price/fixed','price/fixed/index','C','0','0','price:fixed:list'),(907014,'联动价',400,12,'/price/linked/result','price/linked/result/index','C','0','0','price:linked:list'),(907015,'区间价',400,13,'/price/range','price/range/index','C','0','0','price:range:list'),(907016,'结算价格',400,14,'/price/settlement','price/settlement/index','C','0','0','price:settlement:list'),(907017,'废料价格',400,15,'/price/scrap','price/scrap/index','C','0','0','price:scrap:list'),(907018,'家用询价',400,16,'/price/household-inquiry','pages:HouseholdInquiryPage','C','0','0','price:household-inquiry:list')");
      jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r CROSS JOIN sys_menu m WHERE r.role_id IN (10,11) AND m.menu_id BETWEEN 907010 AND 907018");
      String username="mrp07_quote_"+UUID.randomUUID().toString().substring(0,8);
      String password=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,?,'COMMERCIAL','0','0','MRP06',NOW(),'MRP06',NOW())",
          username,"MRP06 browser",new BCryptPasswordEncoder().encode(password));
      long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,11)",user);
      String director="mrp07_director_"+UUID.randomUUID().toString().substring(0,8);
      String directorPassword=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,?,'COMMERCIAL','0','0','MRP07',NOW(),'MRP07',NOW())",
          director,"MRP07 director",new BCryptPasswordEncoder().encode(directorPassword));
      long directorId=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,director);
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,10)",directorId);
      var state=new LinkedHashMap<String,Object>();
      state.put("apiPort",apiPort); state.put("frontendPort",OdsBrowserAcceptance.FRONTEND_PORT);
      state.put("username",username); state.put("password",password);
      state.put("director",director);state.put("directorPassword",directorPassword);
      state.put("oaNo",oa);state.put("itemId",item);state.put("month",month);
      state.put("originalCostVersionId",original);
      try(var c=jdbc.getDataSource().getConnection()) { state.put("jdbcUrl",c.getMetaData().getURL()); }
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
