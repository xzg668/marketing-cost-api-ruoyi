package com.sanhua.marketingcost.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Private handoff from a fresh isolated fixture to the packaged API and worker browser driver. */
final class MonthlyRepriceUncalculatedBrowserSession {
  static void await(JdbcTemplate jdbc,Map<String,Object> sample) throws Exception {
    var state=Path.of(System.getProperty("reprice.test.browser.state"));var release=Path.of(state+".release");
    if(Files.exists(state) || Files.exists(release))throw new IllegalStateException("浏览器样本已存在，不得重放");
    jdbc.update("INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,status,visible) VALUES(40161,'核算与结账',0,5,'cost',NULL,'M','0','0'),(40197,'月度调价',40161,1,'/cost/monthly-reprice','pages:MonthlyRepricePage','C','0','0')");
    jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES(10,40197),(11,40197)");
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
        new org.springframework.core.io.ClassPathResource("db/V351__monthly_reprice_1130_menu.sql"),
        new org.springframework.core.io.ClassPathResource("db/V362__monthly_reprice_uncalculated_menu.sql")).execute(jdbc.getDataSource());
    var accounts=new LinkedHashMap<String,Object>();
    for(String unit:List.of("COMMERCIAL","HOUSEHOLD")) {
      var actors=new LinkedHashMap<String,Object>();
      for(String role:List.of("director","staff")) {
        String name="opt045_"+role+"_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,?,?, '0','0','OPT045',NOW(),'OPT045',NOW())",
            name,"OPT045 "+unit+" "+role,new BCryptPasswordEncoder().encode(password),unit);
        long id=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,name);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role.equals("director")?10:11);
        actors.put(role,Map.of("username",name,"password",password));
      }
      accounts.put(unit,actors);
    }
    var document=new LinkedHashMap<String,Object>();document.put("sample",sample);document.put("accounts",accounts);
    try(var connection=jdbc.getDataSource().getConnection()){document.put("jdbcUrl",connection.getMetaData().getURL());}
    Files.createDirectories(state.getParent());Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,new ObjectMapper().findAndRegisterModules().writeValueAsString(document));
    long deadline=System.nanoTime()+Duration.ofMinutes(20).toNanos();
    while(!Files.exists(release) && System.nanoTime()<deadline)Thread.sleep(250);
    if(!Files.exists(release) || !new ObjectMapper().readTree(Files.readString(release)).path("passed").asBoolean())
      throw new AssertionError("实际API/worker浏览器验收未通过，保留证据");
  }
}
