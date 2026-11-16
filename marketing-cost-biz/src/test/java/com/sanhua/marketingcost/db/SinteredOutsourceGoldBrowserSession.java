package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Holds only a disposable database while released API/worker jars and Chrome use it. */
@Tag("integration")
@EnabledIfSystemProperty(named="gold.browser.state",matches=".+")
class SinteredOutsourceGoldBrowserSession extends SinteredOutsourceGoldIntegrationTest {
  @Test void holdFixture() throws Exception {
    var state=Path.of(System.getProperty("gold.browser.state"));assertThat(Files.exists(state)).isFalse();
    var sample=assembly();
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
        new org.springframework.core.io.ClassPathResource("db/V192__repair_finance_cu_menu_id_collision.sql"),
        new org.springframework.core.io.ClassPathResource("db/V203__material_quote_shape_policy_menu.sql")).execute(jdbc.getDataSource());
    String user="gold_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'委外金盐验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",user,new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,1 FROM sys_user WHERE user_name=?",user);
    var data=new LinkedHashMap<String,Object>();data.put("sample",sample);data.put("username",user);data.put("password",password);
    data.put("jdbcUrl",MYSQL.getJdbcUrl());data.put("databaseUser",MYSQL.getUsername());data.put("databasePassword",MYSQL.getPassword());
    Files.createDirectories(state.getParent());
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(data));
    var release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(40).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    Files.writeString(Path.of(state+".db-verified"),"{\"passed\":true}");
  }
}
