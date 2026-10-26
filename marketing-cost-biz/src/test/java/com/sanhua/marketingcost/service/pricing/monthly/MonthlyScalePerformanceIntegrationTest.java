package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import com.sanhua.marketingcost.service.jjb.*;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.*;

/** MPP01 budgets, native local database, complete production application and monthly pools. */
@Tag("slow")
class MonthlyScalePerformanceIntegrationTest {
  static MonthlyLocalAcceptance fixture;
  static final List<Map<String, Object>> measurements = new ArrayList<>();
  static String run;

  @BeforeAll static void start() throws Exception {
    fixture = new MonthlyLocalAcceptance();
    run = "M13-" + UUID.randomUUID().toString().substring(0, 8);
  }
  @AfterAll static void stop() throws Exception {
    if (fixture != null) {
      fixture.write(run + "-measurements.json", Map.of("database", fixture.settings.path("database").asText(),
          "databaseVersion", fixture.jdbc.queryForObject("SELECT @@version", String.class),
          "java", System.getProperty("java.version"), "architecture", System.getProperty("os.arch"),
          "processors", Runtime.getRuntime().availableProcessors(), "warmups", 5, "samples", 30,
          "measurements", measurements));
      fixture.close();
    }
  }

  @Test void fourActualSourcesAndMonthlyHotPathsScaleByUniqueIdentity() throws Exception {
    var fixed = new ArrayList<String>(); var quantity = new ArrayList<String>(); var scraps = new ArrayList<String>();
    var household = new ArrayList<String>();
    for (int i = 0; i < 1000; i++) {
      fixed.add(run + "-F-" + i); quantity.add(run + "-Q-" + i);
      scraps.add("M13-S-" + run + "-" + i); household.add(run + "-H-" + i);
    }
    fixture.fixed(fixed);
    fixture.jdbc.batchUpdate("INSERT INTO lp_price_range_item(org_code,supplier_code,supplier_name,material_code,"
        + "material_name,unit,range_low,lower_inclusive,range_high,upper_inclusive,range_basis,price_excl_tax,"
        + "effective_from,effective_to,business_unit_type,created_at,updated_at) VALUES('210','M13-SUPPLIER',"
        + "'MPP13',?,'MPP13 synthetic','件',0,1,100,1,'QTY',10,'2026-01-01','2027-01-01','COMMERCIAL',NOW(),NOW())",
        quantity.stream().map(code -> new Object[] {code}).toList());
    fixture.jdbc.batchUpdate("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,"
        + "price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,"
        + "updated_by,updated_at) VALUES('COMMERCIAL','2026-10',?,'MPP13 synthetic','FIXED',5,'{}','IMPORT','2026-10',"
        + "?,'MPP13',NOW(),'MPP13',NOW())", scraps.stream().map(code -> new Object[] {code,"c".repeat(64)}).toList());
    fixture.publish(household, "20.9853");
    var context = fixture.context(run);
    var requests = new ArrayList<MonthlyPriceRequest>();
    for (int i = 0; i < 250; i++) for (var codes : List.of(fixed, quantity, scraps, household))
      requests.add(MonthlyLocalAcceptance.request(codes.get(i), "210"));
    long before = fixture.jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price", Long.class);
    for (int size : List.of(10, 100, 1000)) {
      var subset = new LinkedHashSet<>(requests.subList(0, size));
      var result = measure("cold-four-sources-" + size, size, subset.size(), () -> {
        var candidate = fixture.prices.resolveCandidates(context, subset);
        assertThat(candidate.problems()).isEmpty(); assertThat(candidate.values()).hasSize(size);
        assertThat(candidate.values().values()).allSatisfy(value -> assertThat(value.selection().monthlyPriceId()).isNull());
        return candidate.values().size();
      });
      assertSourceBudget(result);
    }
    assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price", Long.class)).isEqualTo(before);
    var resolved = fixture.prices.resolveCandidates(context, new LinkedHashSet<>(requests));
    var selections = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    resolved.values().forEach((request, value) -> selections.put(request, value.selection()));
    var entries = new ArrayList<>(selections.entrySet());
    for (int start = 0; start < entries.size(); start += 500) {
      var batch = new LinkedHashMap<MonthlyPriceRequest, Selection>();
      entries.subList(start, Math.min(start + 500, entries.size())).forEach(e -> batch.put(e.getKey(), e.getValue()));
      fixture.prices.adopt(context, MonthlyLocalAcceptance.plan(batch));
    }
    for (int size : List.of(10, 100, 1000)) {
      var subset = requests.subList(0, size);
      var hot = measure("hot-" + size, size, size, () -> {
        var result = fixture.prices.resolveCandidates(context, new LinkedHashSet<>(subset));
        assertThat(result.problems()).isEmpty(); assertThat(result.values()).hasSize(size);
        assertThat(result.values().values()).allSatisfy(value -> assertThat(value.selection().monthlyPriceId()).isPositive());
        return result.values().size();
      });
      assertThat((double) hot.get("p95Ms")).isLessThanOrEqualTo(500);
      assertHotSql(hot, (size + 255) / 256);
      var repeated = new ArrayList<MonthlyPriceRequest>();
      for (int i = 0; i < 10; i++) repeated.addAll(subset);
      var duplicates = measure("hot-repeated-" + size, repeated.size(), size, () -> {
        var result = fixture.prices.lookup(repeated);
        assertThat(result.missing()).isEmpty(); assertThat(result.values()).hasSize(size);
        return result.values().size();
      });
      assertHotSql(duplicates, (size + 255) / 256);
    }
    var example = requests.getFirst().family();
    var explain = fixture.jdbc.queryForList("EXPLAIN FORMAT=TRADITIONAL SELECT * FROM lp_material_monthly_price WHERE business_unit_type=? AND family_hash IN (?) ORDER BY id",
        "COMMERCIAL", MonthlyPriceDocumentCodec.sha256(example.canonical()));
    assertThat(explain).allSatisfy(row -> {
      assertThat(row.get("type")).isNotEqualTo("ALL");
      assertThat(row.get("key")).isNotNull();
    });
    fixture.write(run + "-hot-explain.json", explain);
    var sourcePlans=new LinkedHashMap<String,Object>();
    for(var definition:List.of(
        Map.entry("MonthlyFixedSourceMapper.prices",fixed),
        Map.entry("MonthlyQuantityRangeSourceMapper.prices",quantity),
        Map.entry("MonthlyScrapSourceMapper.rules",scraps),
        Map.entry("HouseholdInquiryStorageMapper.current",household),
        Map.entry("MonthlyPriceStorageMapper.pricesByFamilies",fixed))) {
      var parameters=new HashMap<String,Object>();parameters.put("unit","COMMERCIAL");parameters.put("org","210");
      parameters.put("date",java.time.LocalDate.of(2026,10,23));
      parameters.put("codes",definition.getValue().subList(0,256));parameters.put("materials",definition.getValue().subList(0,256));
      parameters.put("hashes",requests.subList(0,256).stream().map(request->MonthlyPriceDocumentCodec.sha256(request.family().canonical())).toList());
      var configuration=fixture.app.getBean(org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration();
      var statement=configuration.getMappedStatement("com.sanhua.marketingcost.mapper.monthly."+definition.getKey());
      var bound=statement.getBoundSql(parameters);var values=new ArrayList<Object>();
      for(var parameter:bound.getParameterMappings())values.add(bound.hasAdditionalParameter(parameter.getProperty())
          ?bound.getAdditionalParameter(parameter.getProperty()):configuration.newMetaObject(parameters).getValue(parameter.getProperty()));
      var plan=fixture.jdbc.queryForList("EXPLAIN FORMAT=TRADITIONAL "+bound.getSql(),values.toArray());
      sourcePlans.put(definition.getKey(),Map.of("identities",256,"parameterCount",values.size(),"sql",bound.getSql(),"plan",plan));
    }
    fixture.write(run+"-source-explain.json",sourcePlans);
    for(var entry:sourcePlans.entrySet())for(var row:(List<Map<String,Object>>)((Map<?,?>)entry.getValue()).get("plan")) {
      assertThat(row.get("type")).as(entry.getKey()).isNotEqualTo("ALL");
      assertThat(row.get("key")).as(entry.getKey()).isNotNull();
    }
  }

  @Test void firstAdoptionOfFiveHundredCompleteActualFactsMeetsFrozenBudget() throws Exception {
    var samples = new ArrayList<Double>(); var sql = new ArrayList<Map<String, Object>>();
    long allocatedBefore = allocated(); long maxHeap = 0;
    for (int sample = -5; sample < 30; sample++) {
      var materials = new ArrayList<String>();
      for (int i = 0; i < 500; i++) materials.add(run + "-A" + (sample + 5) + "-" + i);
      fixture.fixed(materials);
      var context = fixture.context(run + "-adopt-" + sample);
      var requests = materials.stream().map(code -> MonthlyLocalAcceptance.request(code, "210"))
          .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
      var candidates = fixture.prices.resolveCandidates(context, requests);
      assertThat(candidates.problems()).isEmpty(); assertThat(candidates.values()).hasSize(500);
      var selections = new LinkedHashMap<MonthlyPriceRequest, Selection>();
      candidates.values().forEach((request, candidate) -> selections.put(request, candidate.selection()));
      var plan = MonthlyLocalAcceptance.plan(selections);
      MonthlyAcceptanceSqlDriver.reset(); long start = System.nanoTime();
      var saved = fixture.prices.adopt(context, plan);
      double elapsed = (System.nanoTime() - start) / 1e6;
      assertThat(saved.plan().selections()).hasSize(500);
      assertThat(saved.plan().selections().values()).allSatisfy(value -> assertThat(value.monthlyPriceId()).isPositive());
      if (sample >= 0) { samples.add(elapsed); sql.add(MonthlyAcceptanceSqlDriver.snapshot()); }
      maxHeap = Math.max(maxHeap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }
    var result = statistics("adoption-500", 500, 500, samples, sql, allocated() - allocatedBefore, maxHeap, 500);
    measurements.add(result); fixture.write(run + "-adoption-500.json", result);
    assertThat((double) result.get("p95Ms")).isLessThanOrEqualTo(4000);
  }

  @Test void previewAndPublishRealSeventeenAndSyntheticThousandRowsMeetFrozenBudgets() throws Exception {
    var original = getClass().getResourceAsStream("/household-inquiry/real-20261023.xls").readAllBytes();
    for (int size : List.of(17, 1000)) {
      var previewTimes = new ArrayList<Double>(); var publicationTimes = new ArrayList<Double>();
      var previewSql = new ArrayList<Map<String, Object>>(); var publicationSql = new ArrayList<Map<String, Object>>();
      long previewAllocated=0,publicationAllocated=0,peakHeap=0;
      for (int sample = -5; sample < 30; sample++) {
        var codes = new ArrayList<String>();
        for (int i = 0; i < size; i++) codes.add(run + "-P" + (sample + 5) + "-" + i);
        var bytes = size == 17 ? original : fixture.workbook(codes, "20.9853");
        MonthlyAcceptanceSqlDriver.reset(); long start = System.nanoTime(),allocationStart=allocated();
        int parsesBefore=MonthlyLocalAcceptance.workbookParses.get();
        var preview = fixture.inquiries.preview("COMMERCIAL", "MPP13", bytes,
            size == 17 ? "real-20261023.xls" : "MPP13-synthetic-1000.xls", "2026-10",
            new PriceContext("件", "CNY", TaxBasis.EXCLUDED));
        double previewMs = (System.nanoTime() - start) / 1e6;
        if(sample>=0)previewAllocated+=allocated()-allocationStart;
        assertThat(MonthlyLocalAcceptance.workbookParses.get()-parsesBefore).isEqualTo(1);
        assertThat(preview.content().workbook().errors()).isEmpty();
        assertThat(preview.content().rows()).hasSize(size);
        var observedPreview = MonthlyAcceptanceSqlDriver.snapshot();
        MonthlyAcceptanceSqlDriver.reset(); start = System.nanoTime();allocationStart=allocated();
        var published = fixture.inquiries.publish("COMMERCIAL", "MPP13", new PublishRequest(preview.previewId(),
            preview.publishRequestId(), preview.previewFingerprint()));
        double publicationMs = (System.nanoTime() - start) / 1e6;
        if(sample>=0)publicationAllocated+=allocated()-allocationStart;
        assertThat(MonthlyLocalAcceptance.workbookParses.get()-parsesBefore).isEqualTo(1);
        peakHeap=Math.max(peakHeap,heap());
        assertThat(published.rows()).hasSize(size);
        if (sample >= 0) {
          previewTimes.add(previewMs); publicationTimes.add(publicationMs);
          previewSql.add(observedPreview); publicationSql.add(MonthlyAcceptanceSqlDriver.snapshot());
        }
      }
      var preview = statistics("preview-" + size, size, size, previewTimes, previewSql, previewAllocated, peakHeap, size);
      var publication = statistics("publication-" + size, size, size, publicationTimes, publicationSql, publicationAllocated, peakHeap, size);
      preview.put("workbookParsesPerRequest",1);publication.put("workbookParsesPerRequest",0);
      measurements.add(preview); measurements.add(publication);
      fixture.write(run + "-inquiry-" + size + ".json", Map.of("preview", preview, "publication", publication));
      assertThat((double) preview.get("p95Ms")).isLessThanOrEqualTo(size == 17 ? 1000 : 3000);
      assertThat((double) publication.get("p95Ms")).isLessThanOrEqualTo(size == 17 ? 2000 : 8000);
    }
  }

  @Test void actualRendererExportsFiveHundredOrdinaryAndHundredHouseholdWithoutPriceIO() throws Exception {
    var model = JjbExcelRendererTest.model(500, 100, 2);
    var renderer = fixture.app.getBean(JjbExcelRenderer.class);
    final byte[][] latest = {null};
    var result = measure("export-500-100", 600, 600, () -> {
      try (var bytes = new java.io.ByteArrayOutputStream()) {
        renderer.write(model, bytes); latest[0] = bytes.toByteArray(); return model.rows().size();
      } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    });
    assertThat((double) result.get("p95Ms")).isLessThanOrEqualTo(3000);
    for (var sample : (List<Map<String, Object>>) result.get("sql")) assertThat(sample.get("total")).isEqualTo(0L);
    Files.write(fixture.reports.resolve(run + "-renderer-500-100.xlsx"), latest[0]);
    try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(latest[0]))) {
      assertThat(book.getSheet("见机表3").getLastRowNum()).isGreaterThan(600);
      assertThat(book.getSheet("版本依据")).isNotNull();
    }
  }

  static Map<String, Object> measure(String name, int nodes, int unique, IntSupplier operation) throws Exception {
    var times = new ArrayList<Double>(); var sql = new ArrayList<Map<String, Object>>();
    long startAllocation = allocated(), maxHeap = 0; int returned = 0;
    for (int sample = -5; sample < 30; sample++) {
      MonthlyAcceptanceSqlDriver.reset(); long start = System.nanoTime();
      int parsesBefore=MonthlyLocalAcceptance.workbookParses.get();
      returned = operation.getAsInt(); double elapsed = (System.nanoTime() - start) / 1e6;
      assertThat(MonthlyLocalAcceptance.workbookParses.get()).isEqualTo(parsesBefore);
      if (sample >= 0) { times.add(elapsed); sql.add(MonthlyAcceptanceSqlDriver.snapshot()); }
      maxHeap = Math.max(maxHeap, heap());
    }
    var result = statistics(name, nodes, unique, times, sql, allocated() - startAllocation, maxHeap, returned);
    measurements.add(result); fixture.write(run + "-" + name + ".json", result); return result;
  }

  static Map<String, Object> statistics(String name, int nodes, int unique, List<Double> times,
      List<Map<String, Object>> sql, long allocated, long maxHeap, int returned) {
    var sorted = times.stream().sorted().toList(); var result = new LinkedHashMap<String, Object>();
    result.put("name", name); result.put("nodes", nodes); result.put("uniqueIdentities", unique);
    result.put("returnedRows", returned); result.put("elapsedMs", times); result.put("sql", sql);
    result.put("p50Ms", sorted.get(14)); result.put("p95Ms", sorted.get(28)); result.put("p99Ms", sorted.get(29));
    result.put("maxMs", sorted.getLast()); result.put("identitiesPerSecond", 1000.0 * unique * times.size() / times.stream().mapToDouble(Double::doubleValue).sum());
    result.put("threadAllocatedBytes", allocated); result.put("observedHeapUsedMaxBytes", maxHeap);
    result.put("jvmHeapMaxBytes", Runtime.getRuntime().maxMemory()); return result;
  }

  static void assertHotSql(Map<String, Object> result, int batches) {
    for (var sample : (List<Map<String, Object>>) result.get("sql")) {
      assertThat((long) sample.get("total")).isLessThanOrEqualTo(Math.min(12, batches));
      assertThat((Map<String, Long>) sample.get("bySource")).containsOnlyKeys("storage-or-common");
    }
  }
  static void assertSourceBudget(Map<String, Object> result) {
    var limits = Map.of("MonthlyFixedPriceSource", 8, "MonthlyQuantityRangeSource", 20,
        "MonthlyScrapPriceSource", 12, "HouseholdMonthlyPriceSource", 2,
        "MonthlyLegacyLinkedPriceSource", 16, "MonthlyOriginalLinkedPriceSource", 16,
        "MonthlySettlementPriceSource", 8, "MonthlyLegacyRangePriceSource", 20,
        "MonthlyOriginalRangePriceSource", 20);
    int batches = ((int) result.get("uniqueIdentities") + 255) / 256;
    for (var sample : (List<Map<String, Object>>) result.get("sql"))
      ((Map<String, Long>) sample.get("bySource")).forEach((name, count) -> {
        if (limits.containsKey(name)) assertThat(count).as(name).isLessThanOrEqualTo((long) limits.get(name) * batches);
      });
  }
  static long allocated() {
    return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().threadId());
  }
  static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
}
