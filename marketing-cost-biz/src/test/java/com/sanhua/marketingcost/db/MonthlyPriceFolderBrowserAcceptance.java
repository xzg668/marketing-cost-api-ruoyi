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
final class MonthlyPriceFolderBrowserAcceptance {
  record RowData(String org, String material, String supplier, String formula) {}
  static RowData row(String code, String formula) { return new RowData("210", code, "SUP", formula); }
  static byte[] workbook(RowData... rows) throws Exception {
    try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var factors = book.createSheet("因素"); var fh = factors.createRow(0);
      String[] titles = {"序号", "名称", "简称", "取价来源", "价格", "单位"};
      for (int i = 0; i < titles.length; i++) fh.createCell(i).setCellValue(titles[i]);
      var f = factors.createRow(1); String[] values = {"1", "铜", "Cu", "平均价"};
      for (int i = 0; i < values.length; i++) f.createCell(i).setCellValue(values[i]);
      f.createCell(4).setCellValue(109.114); f.createCell(5).setCellValue("公斤");
      var main = book.createSheet("ImportData"); var header = main.createRow(0);
      String[] names = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
      for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
      for (int i = 0; i < rows.length; i++) {
        var data = rows[i]; var row = main.createRow(i + 1);
        String[] cells = {data.org(), data.material(), "测试供应商", data.supplier(), "", "件"};
        for (int j = 0; j < cells.length; j++) row.createCell(j).setCellValue(cells[j]);
        if (data.formula() != null) row.createCell(6).setCellFormula(data.formula());
        row.createCell(7).setCellValue(false);
      }
      book.getCreationHelper().createFormulaEvaluator().evaluateAll(); book.write(out); return out.toByteArray();
    }
  }
  static byte[] rangeWorkbook(String code, double start) throws Exception {
    try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook(row(code, "99")))); var out = new ByteArrayOutputStream()) {
      book.getSheet("ImportData").getRow(1).getCell(4).setCellValue("SPEC");
      var matrix = book.createSheet("矩阵"); matrix.createRow(0).createCell(0).setCellValue("铜价区间表");
      var h = matrix.createRow(1); h.createCell(0).setCellValue("U9代码"); h.createCell(1).setCellValue("图号"); h.createCell(2).setCellValue("供应商名称");
      var tax = matrix.createRow(2); var data = matrix.createRow(3);
      data.createCell(0).setCellValue(code); data.createCell(1).setCellValue("SPEC"); data.createCell(2).setCellValue("测试供应商");
      for (int i = 0; i < 4; i++) {
        int c = i * 2 + 3; h.createCell(c).setCellValue((90000 + i * 5000) + "-" + (94999 + i * 5000));
        tax.createCell(c).setCellValue("不含税"); tax.createCell(c + 1).setCellValue("含13%税价");
        data.createCell(c).setCellValue(start + i); data.createCell(c + 1).setCellValue((start + i) * 1.13);
      }
      book.write(out); return out.toByteArray();
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
      var state = Path.of(System.getProperty("folder.browser.state"));
      var release = Path.of(state + ".release");
      assertThat(Files.exists(state)).as("fresh session only").isFalse();
      assertThat(Files.exists(release)).isFalse();
      new ResourceDatabasePopulator(
              new ClassPathResource("db/V346__monthly_material_price_menu.sql"),
              new ClassPathResource("db/V351__monthly_reprice_1130_menu.sql"))
          .execute(jdbc.getDataSource());
      String name = "folder_" + UUID.randomUUID().toString().substring(0, 8),
          password = UUID.randomUUID().toString();
      jdbc.update(
          "INSERT INTO"
              + " sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_time)"
              + " VALUES(?,?,?,'COMMERCIAL','0','0','INQ',NOW(),NOW())",
          name,
          "文件夹验收",
          new BCryptPasswordEncoder().encode(password));
      long user =
          jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, name);
      jdbc.update(
          "INSERT INTO sys_user_role(user_id,role_id) SELECT ?,role_id FROM sys_role WHERE"
              + " role_key='admin' AND del_flag='0'",
          user);
      var folder = Files.createTempDirectory("folder-browser-files-");
      var original = folder.resolve("01联动.xlsx");
      var replacement = Path.of(state + ".replacement.xlsx");
      Files.write(original, workbook(row(material, "因素!E2*0.1"), row(material, "因素!E2*0.1+2"), row("FOLDER-NO-PRICE", null)));
      Files.write(folder.resolve("02区间.xlsx"), rangeWorkbook("FOLDER-RANGE", 15));
      Files.writeString(folder.resolve("03损坏.xlsx"), "broken workbook fixture");
      Files.writeString(folder.resolve("说明.txt"), "仅参考资料");
      Files.write(replacement, workbook(row("FOLDER-REPAIRED", "因素!E2*0.1+3")));
      var config = new LinkedHashMap<String, Object>();
      config.put("apiPort", port);
      config.put("frontendPort", OdsBrowserAcceptance.FRONTEND_PORT);
      config.put("username", name);
      config.put("password", password);
      config.put("token", jwt.generateToken(name, "COMMERCIAL"));
      for (String tenant : List.of("COMMERCIAL", "HOUSEHOLD")) {
        String restricted = "folder_check_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
            "INSERT INTO"
                + " sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_time)"
                + " VALUES(?,?,?,?, '0','0','INQ',NOW(),NOW())",
            restricted,
            "文件夹权限验收",
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
      config.put("folder", folder.toString());
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
          .containsExactly(new java.math.BigDecimal("11"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT source_type FROM lp_material_monthly_price WHERE material_code=?",
                  String.class,
                  material))
          .isEqualTo("LINKED");
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
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_price_import WHERE status='COMPLETE'", Integer.class))
          .isEqualTo(1);
    } catch (Exception failure) {
      throw new AssertionError("Folder browser acceptance failed", failure);
    }
  }
}
