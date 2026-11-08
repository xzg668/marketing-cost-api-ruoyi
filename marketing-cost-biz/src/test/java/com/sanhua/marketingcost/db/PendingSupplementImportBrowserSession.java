package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 真实页面读取隔离 API，导入使用相同财务业务接口；不调用外部 OA。 */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15329",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class PendingSupplementImportBrowserSession extends PendingSupplementPublicImportIntegrationTest {
  @LocalServerPort int port;

  @Test void browserSession() throws Exception {
    Path state=Path.of(System.getProperty("pending.import.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    String username="pending_"+UUID.randomUUID().toString().substring(0,8), password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,employee_no,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'补齐资料验收',?,'PENDING001','COMMERCIAL','0','0','PENDING_TEST',NOW(),'PENDING_TEST',NOW())",username,new BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var json=new ObjectMapper();
    var before=protectedRows();
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"username",username,"password",password,
        "oaNo",oaNo,"itemId",itemId,"month",month)));
    boolean price=false,rate=false;
    long deadline=System.nanoTime()+Duration.ofMinutes(12).toNanos();
    Path release=Path.of(state+".release");
    while(!Files.exists(release)&&System.nanoTime()<deadline) {
      if(!price&&Files.exists(Path.of(state+".import-price"))) {
        importPrice();price=true;Files.writeString(Path.of(state+".price-imported"),"ok");
      }
      if(!rate&&Files.exists(Path.of(state+".import-rate"))) {
        importRate();rate=true;Files.writeString(Path.of(state+".rate-imported"),"ok");
      }
      Thread.sleep(200);
    }
    assertThat(Files.exists(release)).as("浏览器验收已完成").isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(price&&rate).isTrue();
    assertThat(protectedRows()).isEqualTo(before);
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,
        "supplementRowsAdded",0,"previewRowsAdded",0,"originalWorkspaceUnchanged",true,
        "monthlyPricesUnchanged",true,"costVersionsUnchanged",true)));
  }
}
