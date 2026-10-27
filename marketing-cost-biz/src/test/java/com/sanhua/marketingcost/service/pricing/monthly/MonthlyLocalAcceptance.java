package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.*;
import com.sanhua.marketingcost.MarketingCostApiApplication;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryService;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

/** A separate empty schema on the native local server; credentials stay in a private file. */
final class MonthlyLocalAcceptance implements AutoCloseable {
  static final java.util.concurrent.atomic.AtomicInteger workbookParses = new java.util.concurrent.atomic.AtomicInteger();
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final JsonNode settings;
  final ConfigurableApplicationContext app;
  final JdbcTemplate jdbc;
  final MonthlyMaterialPrices prices;
  final HouseholdInquiryService inquiries;
  final Path reports;

  MonthlyLocalAcceptance() throws Exception {
    var file = Path.of(System.getProperty("mpp13.private-config", "/tmp/mpp13-performance-private.json"));
    settings = json.readTree(Files.readString(file));
    String database = settings.path("database").asText();
    if (!database.matches("mpp_acceptance_[0-9]{8}_[0-9]{6}") || database.equals("mpp_acceptance_20261023_115640"))
      throw new IllegalArgumentException("MPP13 requires its dedicated empty performance schema");
    try (var connection = java.sql.DriverManager.getConnection(settings.path("url").asText(),
        settings.path("username").asText(), settings.path("password").asText())) {
      connection.setReadOnly(true);
      try (var statement = connection.createStatement(); var identity = statement.executeQuery("SELECT DATABASE(),@@server_uuid,@@version")) {
        if (!identity.next() || !database.equals(identity.getString(1))
            || !settings.path("uuid").asText().equals(identity.getString(2))
            || !identity.getString(3).startsWith(settings.path("expectedVersion").asText()))
          throw new IllegalStateException("Unexpected database before MPP13 application startup");
      }
    }
    reports = Path.of(settings.path("reportDirectory").asText());
    Files.createDirectories(reports);
    var work = Path.of(settings.path("workDirectory").asText());
    Files.createDirectories(work);
    System.setProperty("LOG_DIR", work.resolve("logs-" + ProcessHandle.current().pid()).toString());
    var properties = new HashMap<String, Object>();
    properties.put("spring.datasource.url", settings.path("url").asText()
        + "&connectionAttributes=mpp13_pid:" + ProcessHandle.current().pid());
    properties.put("spring.datasource.username", settings.path("username").asText());
    properties.put("spring.datasource.password", settings.path("password").asText());
    properties.put("spring.datasource.driver-class-name", MonthlyAcceptanceSqlDriver.class.getName());
    properties.put("spring.datasource.hikari.maximum-pool-size", 40);
    properties.put("spring.datasource.hikari.minimum-idle", 1);
    properties.put("server.address", "127.0.0.1");
    properties.put("server.port", 0);
    properties.put("jwt.secret", settings.path("jwtSecret").asText());
    for (String key : List.of("srm.fixed-price.enabled", "monthly.reprice.enabled", "cms.sync.publish.enabled",
        "integration.oa-person-directory.enabled", "integration.oa-person-directory.run-at-startup",
        "quote.drawing-verification.enabled", "cost-run.worker.enabled")) properties.put(key, false);
    for (String key : List.of("integration.oa-auth.base-url", "integration.oa-workflow.base-url",
        "integration.oa-workflow.reject-base-url", "integration.oa-oauth.base-url",
        "integration.oa-person-directory.query-base-url", "integration.oa-person-directory.detail-base-url"))
      properties.put(key, "http://127.0.0.1:9");
    for (String key : List.of("integration.oa-auth.call-sys-code", "integration.oa-auth.corp-id",
        "integration.oa-auth.app-key", "integration.oa-auth.app-secret"))
      properties.put(key, "MPP13-ISOLATED-DISABLED");
    properties.put("price.household.workbooks-root", work.resolve("household").toString());
    properties.put("quote.technical-data.attachments-root", work.resolve("technical").toString());
    properties.put("logging.level.com.sanhua.marketingcost", "WARN");
    var application = new SpringApplication(MarketingCostApiApplication.class);
    application.addInitializers(context -> context.getEnvironment().getPropertySources()
        .addFirst(new MapPropertySource("MPP13-isolated-test", properties)));
    application.addInitializers(context -> context.getBeanFactory().addBeanPostProcessor(
        new org.springframework.beans.factory.config.BeanPostProcessor() {
          @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryWorkbookParser parser)) return bean;
            return new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryWorkbookParser() {
              @Override public WorkbookData parse(byte[] bytes, String originalName) {
                workbookParses.incrementAndGet(); return parser.parse(bytes, originalName);
              }
            };
          }
        }));
    app = application.run();
    jdbc = app.getBean(JdbcTemplate.class);
    if (!database.equals(jdbc.queryForObject("SELECT DATABASE()", String.class))
        || !settings.path("uuid").asText().equals(jdbc.queryForObject("SELECT @@server_uuid", String.class))
        || !jdbc.queryForObject("SELECT @@version", String.class).startsWith(settings.path("expectedVersion").asText())) {
      app.close(); throw new IllegalStateException("Unexpected MPP13 database identity");
    }
    prices = app.getBean(MonthlyMaterialPrices.class);
    inquiries = app.getBean(HouseholdInquiryService.class);
  }

  MonthlyMaterialPrices.Context context(String identity) {
    return new MonthlyMaterialPrices.Context("COMMERCIAL", identity, 1L, identity,
        YearMonth.of(2026, 10), LocalDateTime.of(2026, 10, 23, 0, 0), "MPP13", "210");
  }

  static MonthlyPriceRequest request(String material, String org) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
        material, null, material.startsWith("M13-S-") ? "KG" : "件", "CNY", TaxBasis.EXCLUDED, null),
        org, NonCuConditions.empty(), BigDecimal.ONE);
  }

  void fixed(List<String> materials) {
    jdbc.batchUpdate("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,"
        + "tax_included,supplier_code,supplier_name,effective_from,effective_to,business_unit_type,source_kind,"
        + "source_type,source_system,source_batch_no,created_at,updated_at) VALUES('210',?,'MPP13 synthetic',"
        + "'件',12.345678901234,0,'M13-SUPPLIER','MPP13 synthetic','2026-01-01','2027-01-01','COMMERCIAL',"
        + "'PUBLIC','PURCHASE_FIXED','SRM','MPP13-ISOLATED',NOW(),NOW())",
        materials.stream().map(code -> new Object[] {code}).toList());
  }

  byte[] workbook(List<String> materials, String price) throws Exception {
    try (var book = new HSSFWorkbook(); var bytes = new java.io.ByteArrayOutputStream()) {
      var sheet = book.createSheet("10月线圈询价");
      sheet.createRow(0).createCell(1).setCellValue("明细表2");
      var header = sheet.createRow(1);
      Map.of(0, "料号", 1, "名称", 2, "规格", 4, "报价总成本（含两项费用、不含运费）", 13, "两项费用比例%")
          .forEach((column, text) -> header.createCell(column).setCellValue(text));
      for (int i = 0; i < materials.size(); i++) {
        var row = sheet.createRow(i + 2);
        row.createCell(0).setCellValue(materials.get(i)); row.createCell(1).setCellValue("MPP13 合成线圈");
        row.createCell(2).setCellValue("SPEC"); row.createCell(4).setCellValue(price);
        row.createCell(13).setCellValue("8.5");
      }
      book.write(bytes); return bytes.toByteArray();
    }
  }

  void publish(List<String> materials, String price) throws Exception {
    var preview = inquiries.preview("COMMERCIAL", "MPP13", workbook(materials, price),
        "MPP13-synthetic.xls", "2026-10");
    if (!preview.content().workbook().errors().isEmpty()) throw new IllegalStateException("Invalid fixture");
    inquiries.publish("COMMERCIAL", "MPP13", new PublishRequest(preview.previewId(),
        preview.publishRequestId(), preview.previewFingerprint()));
  }

  static MonthlyAdoptionPlan plan(Map<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection> selections) {
    return new MonthlyAdoptionPlan(selections, selections.keySet().stream()
        .map(request -> new MonthlyAdoptionPlan.Use("TOP/" + request.family().materialCode(), request, BigDecimal.ONE))
        .toList(), Set.of());
  }

  void write(String name, Object value) throws Exception {
    json.writerWithDefaultPrettyPrinter().writeValue(reports.resolve(name).toFile(), value);
  }
  @Override public void close() { app.close(); }
}
