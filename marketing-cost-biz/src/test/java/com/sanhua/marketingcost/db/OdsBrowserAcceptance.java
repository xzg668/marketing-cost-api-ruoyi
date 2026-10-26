package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Explicit local browser evidence, backed by this run's disposable MySQL and real HTTP application. */
final class OdsBrowserAcceptance {
  static final int FRONTEND_PORT=availablePort();
  private static int availablePort() {
    try(var socket=new ServerSocket(0)){return socket.getLocalPort();}
    catch(java.io.IOException failure){throw new ExceptionInInitializerError(failure);}
  }
  static void verify(JdbcTemplate jdbc,int apiPort,String token,String oaNo,long itemId,String material,
      String month,long monthlyId,long costVersionId) {
    Path privateConfig=null;
    try {
      new ResourceDatabasePopulator(new ClassPathResource("db/V346__monthly_material_price_menu.sql")).execute(jdbc.getDataSource());
      var script=Path.of(System.getProperty("ods.browser.script")).toAbsolutePath();
      var reports=script.getParent().resolve(System.getProperty("ods.browser.attempt","browser"));Files.createDirectories(reports);
      int frontendPort=FRONTEND_PORT;
      privateConfig=Files.createTempFile("ods-browser-", ".json");Files.setPosixFilePermissions(privateConfig,PosixFilePermissions.fromString("rw-------"));
      var config=new LinkedHashMap<String,Object>();
      config.put("frontendPort",frontendPort);config.put("apiPort",apiPort);config.put("token",token);
      config.put("oaNo",oaNo);config.put("itemId",itemId);config.put("material",material);config.put("month",month);
      config.put("monthlyId",monthlyId);config.put("costVersionId",costVersionId);config.put("reports",reports.toString());
      Files.writeString(privateConfig,new ObjectMapper().writeValueAsString(config));
      var root=script.getParent().getParent().getParent();
      String python=System.getProperty("ods.browser.python","python3");
      String helper=System.getProperty("ods.browser.server-helper");
      if(helper==null)throw new IllegalArgumentException("Explicit local webapp-testing helper required");
      var process=new ProcessBuilder(python,helper,"--server",
          "npm --prefix '"+root.resolve("marketing-cost")+"' run dev -- --host 127.0.0.1 --port "+frontendPort+" --strictPort",
          "--port",Integer.toString(frontendPort),"--",python,script.toString(),privateConfig.toString());
      process.environment().put("VITE_API_PROXY_TARGET","http://127.0.0.1:"+apiPort);
      process.redirectErrorStream(true);process.redirectOutput(reports.resolve("process.log").toFile());
      var started=process.start();
      if(!started.waitFor(60,TimeUnit.SECONDS)){started.destroyForcibly();throw new AssertionError("Browser verification timed out; inspect private-free browser reports");}
      assertThat(started.exitValue()).as("real browser process; see "+reports).isZero();
      var result=new ObjectMapper().readTree(Files.readString(reports.resolve("verification.json")));
      assertThat(result.path("passed").asBoolean()).isTrue();
    } catch(Exception failure) { throw new AssertionError("ODS browser verification failed",failure); }
    finally { if(privateConfig!=null)try{Files.deleteIfExists(privateConfig);}catch(java.io.IOException ignored){} }
  }
}
