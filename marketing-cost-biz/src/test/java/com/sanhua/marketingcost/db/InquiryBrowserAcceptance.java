package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Only this invocation's disposable database; the browser owns all imports and first costing. */
final class InquiryBrowserAcceptance {
  static byte[] fixedWorkbook(String model, String price) throws Exception {
    try (var book = new XSSFWorkbook();
        var bytes = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("更换名称的新询价");
      var header = sheet.createRow(0);
      var labels = List.of("料号", "规格型号", "供方名称", "价格类型", "批量价总价（元）", "不含税价格");
      for (int i = 0; i < labels.size(); i++) header.createCell(i).setCellValue(labels.get(i));
      var row = sheet.createRow(1);
      row.createCell(1).setCellValue(model);
      row.createCell(3).setCellValue("固定价");
      row.createCell(4).setCellValue(price);
      row.createCell(5).setCellValue("999");
      book.write(bytes);
      return bytes.toByteArray();
    }
  }

  static void await(
      JdbcTemplate jdbc,
      int port,
      JwtUtils jwt,
      String oaNo,
      long formId,
      long itemId,
      String material,
      String month) {
    try {
      var state = Path.of(System.getProperty("inquiry.browser.state"));
      var release = Path.of(state + ".release");
      assertThat(Files.exists(state)).as("fresh session only").isFalse();
      assertThat(Files.exists(release)).isFalse();
      new ResourceDatabasePopulator(
              new ClassPathResource("db/V346__monthly_material_price_menu.sql"),
              new ClassPathResource("db/V357__inquiry_price_menu.sql"))
          .execute(jdbc.getDataSource());
      String name = "inquiry_" + UUID.randomUUID().toString().substring(0, 8),
          password = UUID.randomUUID().toString();
      jdbc.update(
          "INSERT INTO"
              + " sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_time)"
              + " VALUES(?,?,?,'COMMERCIAL','0','0','INQ',NOW(),NOW())",
          name,
          "询价验收",
          new BCryptPasswordEncoder().encode(password));
      long user =
          jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, name);
      jdbc.update(
          "INSERT INTO sys_user_role(user_id,role_id) SELECT ?,role_id FROM sys_role WHERE"
              + " role_key='admin' AND del_flag='0'",
          user);
      var original = Path.of(state + ".original.xls");
      var replacement = Path.of(state + ".replacement.xlsx");
      try (var input =
          new ClassPathResource("inquiry-pricing/采购件询价录入格式1031.xls").getInputStream()) {
        Files.copy(input, original);
      }
      Files.write(replacement, fixedWorkbook("YCQC02-043002", "1"));
      var config = new LinkedHashMap<String, Object>();
      config.put("apiPort", port);
      config.put("frontendPort", OdsBrowserAcceptance.FRONTEND_PORT);
      config.put("username", name);
      config.put("password", password);
      config.put("token", jwt.generateToken(name, "COMMERCIAL"));
      for (String tenant : List.of("COMMERCIAL", "HOUSEHOLD")) {
        String restricted = "inquiry_check_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
            "INSERT INTO"
                + " sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_time)"
                + " VALUES(?,?,?,?, '0','0','INQ',NOW(),NOW())",
            restricted,
            "询价权限验收",
            new BCryptPasswordEncoder().encode(UUID.randomUUID().toString()),
            tenant);
        if (tenant.equals("HOUSEHOLD"))
          jdbc.update(
              "INSERT INTO sys_user_role(user_id,role_id) SELECT u.user_id,r.role_id FROM sys_user"
                  + " u JOIN sys_role r ON r.role_key='admin' AND r.del_flag='0' WHERE"
                  + " u.user_name=?",
              restricted);
        config.put(
            tenant.equals("HOUSEHOLD") ? "otherTenantToken" : "deniedToken",
            jwt.generateToken(restricted, tenant));
      }
      config.put("oaNo", oaNo);
      config.put("formId", formId);
      config.put("itemId", itemId);
      config.put("material", material);
      config.put("month", month);
      config.put("original", original.toString());
      config.put("replacement", replacement.toString());
      Files.createFile(
          state,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(state, new ObjectMapper().writeValueAsString(config));
      long deadline = System.nanoTime() + Duration.ofMinutes(45).toNanos();
      while (!Files.exists(release) && System.nanoTime() < deadline) Thread.sleep(250);
      assertThat(Files.exists(release)).as("real browser completed").isTrue();
      var evidence = new ObjectMapper().readTree(Files.readString(release));
      assertThat(evidence.path("passed").asBoolean()).isTrue();
      assertThat(
              jdbc.queryForList(
                  "SELECT unit_price FROM lp_material_monthly_price WHERE material_code=? ORDER BY"
                      + " id",
                  java.math.BigDecimal.class,
                  material))
          .usingComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
          .containsExactly(new java.math.BigDecimal("0.0442"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT source_type FROM lp_material_monthly_price WHERE material_code=?",
                  String.class,
                  material))
          .isEqualTo("INQUIRY");
      long cost = evidence.path("costVersionId").asLong();
      assertThat(
              jdbc.queryForObject(
                  "SELECT oa_form_item_id FROM lp_quote_cost_run_version WHERE id=?",
                  Long.class,
                  cost))
          .isEqualTo(itemId);
      assertThat(
              jdbc.queryForObject(
                  "SELECT pricing_month FROM lp_quote_cost_run_version WHERE id=?",
                  String.class,
                  cost))
          .isEqualTo(month);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_inquiry_import_batch", Integer.class))
          .isGreaterThanOrEqualTo(2);
    } catch (Exception failure) {
      throw new AssertionError("Inquiry browser acceptance failed", failure);
    }
  }
}
