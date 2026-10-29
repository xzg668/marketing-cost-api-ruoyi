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
final class MonthlyRepriceBrowserSession {
  static void await(JdbcTemplate jdbc,int apiPort,String oa,long item,String month,long original,long repriced) {
    String location=System.getProperty("mrp06.browser.state");
    if (location == null) return;
    try {
      var target=Path.of(location); var release=Path.of(location+".release");
      if (Files.exists(target) || Files.exists(release)) throw new IllegalStateException("Browser handoff already exists");
      String username="mrp06_"+UUID.randomUUID().toString().substring(0,8);
      String password=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,?,'COMMERCIAL','0','0','MRP06',NOW(),'MRP06',NOW())",
          username,"MRP06 browser",new BCryptPasswordEncoder().encode(password));
      long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
      var state=new LinkedHashMap<String,Object>();
      state.put("apiPort",apiPort); state.put("frontendPort",OdsBrowserAcceptance.FRONTEND_PORT);
      state.put("username",username); state.put("password",password);
      state.put("oaNo",oa);state.put("itemId",item);state.put("month",month);
      state.put("originalCostVersionId",original);state.put("repriceCostVersionId",repriced);
      try(var c=jdbc.getDataSource().getConnection()) { state.put("jdbcUrl",c.getMetaData().getURL()); }
      Files.createFile(target,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(target,new ObjectMapper().writeValueAsString(state));
      long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
      while (!Files.exists(release) && System.nanoTime()<deadline) Thread.sleep(250);
      if (!Files.exists(release)) throw new AssertionError("Browser acceptance did not release this disposable runtime");
      var result=new ObjectMapper().readTree(Files.readString(release));
      if (!result.path("passed").asBoolean()) throw new AssertionError("Browser acceptance failed; inspect stage evidence");
    } catch(Exception failure) { throw new AssertionError("MRP06 disposable browser handoff failed",failure); }
  }
}
