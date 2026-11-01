package com.sanhua.marketingcost.service.pricing.inquiry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class InquiryImportRefreshIntegrationTest {
  static final InquiryDatabaseTestSupport db = new InquiryDatabaseTestSupport();

  @BeforeAll
  static void start() throws Exception {
    db.start();
    db.publishFinance("2026-09", "CU_90000");
  }

  @AfterAll
  static void stop() {
    db.close();
  }

  @BeforeEach
  void clean() {
    db.clearInquiries();
  }

  @Test
  void importRefreshAndFinancePublicationRefreshPreserveOriginalAndMonthlyHistory()
      throws Exception {
    String month = "2027-01", next = "2027-02";
    db.publishFinance(month, "CU_90000");
    var original = InquiryWorkbookParserTest.original();
    var imported = db.importFile(month, original);
    assertThat(imported.refresh().ready()).isEqualTo(2);
    assertThat(db.service.original("COMMERCIAL", imported.batchId()).bytes()).isEqualTo(original);
    var first = db.prices(month);
    var oldPrice = linked(first).price();
    assertThat(fixed(first).price()).isEqualByComparingTo("0.0442");
    assertThat(linked(first).calculation().get("financeMonth")).isEqualTo(month);
    long oldRows = count("lp_inquiry_effective_price");
    db.publishFinance(next, "CU_100000");
    var financial =
        db.finance.read(
            new com.sanhua.marketingcost.dto.FinanceFactorImportContext(
                "COMMERCIAL", "210", next, null, null),
            com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition.GENERAL,
            null);
    var expected =
        financial
            .required("AVG|1#CU")
            .price()
            .multiply(new BigDecimal("0.15"))
            .add(financial.required("AVG|1#ZN").price().multiply(new BigDecimal("0.1")))
            .add(
                financial
                    .required("AVG|美国柜装黄铜")
                    .price()
                    .multiply(new BigDecimal("0.75"))
                    .multiply(new BigDecimal("1.05")))
            .multiply(new BigDecimal("1.02"))
            .add(new BigDecimal("4.6"))
            .multiply(new BigDecimal("1.1905"))
            .divide(new BigDecimal("1.13"), 18, java.math.RoundingMode.HALF_UP);
    assertThat(linked(db.prices(next)).price())
        .isCloseTo(expected, within(new BigDecimal("0.000000000001")))
        .isNotEqualByComparingTo(oldPrice);
    assertThat(linked(db.prices(next)).calculation().get("financeMonth")).isEqualTo(next);
    assertThat(linked(db.prices(month)).price()).isEqualByComparingTo(oldPrice);
    assertThat(count("lp_inquiry_import_batch")).isEqualTo(1);
    assertThat(count("lp_inquiry_effective_price")).isEqualTo(oldRows + 2);
    db.refresh.refresh("COMMERCIAL", next, "MONTHLY_REPRICE", "director");
    assertThat(count("lp_inquiry_effective_price")).isEqualTo(oldRows + 2);
  }

  @Test
  void importWithoutTargetMonthBaseWaitsThenActualPublicationRefreshesIt() throws Exception {
    String month = "2027-03";
    var imported = db.importFile(month, InquiryWorkbookParserTest.original());
    assertThat(imported.refresh().waiting()).isEqualTo(1);
    assertThat(imported.refresh().ready()).isEqualTo(1);
    assertThat(linked(db.prices(month)).status()).isEqualTo("WAITING_BASE");
    assertThat(linked(db.prices(month)).price()).isNull();
    db.publishFinance(month, "CU_100000");
    assertThat(linked(db.prices(month)).status()).isEqualTo("READY");
    assertThat(linked(db.prices(month)).calculation().get("financeMonth")).isEqualTo(month);
    assertThat(count("lp_inquiry_effective_price")).isEqualTo(3);
  }

  @Test
  void sameMonthImportUpdatesOnlyMatchingIdentityAndExactRequestRetryIsIdempotent()
      throws Exception {
    String month = "2027-04";
    db.publishFinance(month, "CU_90000");
    byte[] bytes = InquiryWorkbookParserTest.original();
    String request = UUID.randomUUID().toString();
    var first = db.service.publish("COMMERCIAL", month, "fixture", request, "原表.xls", bytes);
    var second = db.service.publish("COMMERCIAL", month, "fixture", request, "原表.xls", bytes);
    assertThat(second.batchId()).isEqualTo(first.batchId());
    assertThat(count("lp_inquiry_rule")).isEqualTo(2);
    var changed =
        InquiryWorkbookParserTest.edit(
            book -> {
              var s = book.getSheetAt(0);
              s.removeRow(s.getRow(1));
              s.getRow(2).getCell(12).setCellValue("0.0555");
            });
    db.importFile(month, changed);
    assertThat(db.prices(month)).hasSize(2);
    assertThat(fixed(db.prices(month)).price()).isEqualByComparingTo("0.0555");
    assertThat(db.service.batch("COMMERCIAL", first.batchId()).getLast().rule().originalPrice())
        .isEqualByComparingTo("0.0442");
    assertThatThrownBy(
            () -> db.service.publish("COMMERCIAL", "2027-05", "fixture", request, "原表.xls", bytes))
        .hasMessageContaining("不同月份");
    assertThat(db.service.history("HOUSEHOLD", 1, 20).total()).isZero();
    assertThatThrownBy(() -> db.service.original("HOUSEHOLD", first.batchId()))
        .hasMessageContaining("询价导入记录不存在");
  }

  @Test
  void laterImportIsNotBackdatedIntoPreviousMonthAndChangedSpecificationDoesNotReviveOldRule()
      throws Exception {
    var old =
        InquiryWorkbookParserTest.edit(
            book -> {
              var r = book.getSheetAt(0).getRow(2);
              r.getCell(0).setCellValue("001234");
            });
    db.importFile("2027-06", old);
    var updated =
        InquiryWorkbookParserTest.edit(
            book -> {
              var r = book.getSheetAt(0).getRow(2);
              r.getCell(0).setCellValue("001234");
              r.getCell(2).setCellValue("新规格");
              r.getCell(12).setCellValue("1.123456789123456789");
            });
    db.importFile("2027-07", updated);
    assertThat(fixed(db.prices("2027-06")).price()).isEqualByComparingTo("0.0442");
    assertThat(fixed(db.prices("2027-07")).price()).isEqualByComparingTo("1.123456789123456789");
    assertThat(db.repository.matching("COMMERCIAL", "2027-07", Set.of(), Set.of("YCQC02-043002")))
        .isEmpty();
    assertThat(db.repository.matching("COMMERCIAL", "2027-07", Set.of("001234"), Set.of()))
        .hasSize(1);
  }

  @Test
  void failedRefreshRollsBackTheWholeImport() throws Exception {
    var broken =
        mock(
            InquiryPriceRefreshService.class,
            withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    when(broken.refreshInTransaction(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("controlled failure"));
    var service =
        new InquiryPriceService(db.repository, new InquiryWorkbookParser(), broken, db.manager);
    assertThatThrownBy(
            () ->
                service.publish(
                    "COMMERCIAL",
                    "2027-08",
                    "fixture",
                    UUID.randomUUID().toString(),
                    "原件.xls",
                    InquiryWorkbookParserTest.original()))
        .hasMessageContaining("controlled failure");
    assertThat(count("lp_inquiry_import_batch")).isZero();
    assertThat(count("lp_inquiry_rule")).isZero();
  }

  @Test
  void simultaneousSameRequestPublishesOnceAndRefreshRemainsIdempotent() throws Exception {
    String month = "2027-09", request = UUID.randomUUID().toString();
    db.publishFinance(month, "CU_90000");
    byte[] bytes = InquiryWorkbookParserTest.original();
    try (var executor = Executors.newFixedThreadPool(3)) {
      var results = new ArrayList<Future<InquiryPriceService.Published>>();
      for (int i = 0; i < 3; i++)
        results.add(
            executor.submit(
                () ->
                    db.service.publish("COMMERCIAL", month, "fixture", request, "原表.xls", bytes)));
      var ids = new HashSet<Long>();
      for (var result : results) ids.add(result.get(30, TimeUnit.SECONDS).batchId());
      assertThat(ids).hasSize(1);
    }
    assertThat(count("lp_inquiry_import_batch")).isEqualTo(1);
    assertThat(count("lp_inquiry_rule")).isEqualTo(2);
    assertThat(count("lp_inquiry_effective_price")).isEqualTo(2);
  }

  @Test
  void concurrentFinancePublicationAndImportFinishOnTheSameLatestFinancialRevision()
      throws Exception {
    String month = "2027-10";
    db.publishFinance(month, "CU_90000");
    byte[] bytes = InquiryWorkbookParserTest.original();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var imported =
          pool.submit(
              () -> {
                start.await();
                return db.importFile(month, bytes);
              });
      var published =
          pool.submit(
              () -> {
                start.await();
                db.publishFinance(month, "CU_100000");
                return true;
              });
      start.countDown();
      imported.get(40, TimeUnit.SECONDS);
      published.get(40, TimeUnit.SECONDS);
    }
    var latest =
        db.finance.read(
            new com.sanhua.marketingcost.dto.FinanceFactorImportContext(
                "COMMERCIAL", "210", month, null, null),
            com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition.GENERAL,
            null);
    var current = linked(db.prices(month));
    assertThat(((Number) current.calculation().get("financeBatchId")).longValue())
        .isEqualTo(latest.batchId());
    assertThat(current.status()).isEqualTo("READY");
  }

  @Test
  void failedInquiryStorageRollsBackTheActualFinancialPublication() throws Exception {
    String month = "2027-11";
    db.publishFinance(month, "CU_90000");
    db.importFile(month, InquiryWorkbookParserTest.original());
    long before = count("lp_factor_upload_batch");
    BigDecimal amount = linked(db.prices(month)).price();
    db.jdbc.execute(
        "CREATE TRIGGER inquiry_test_fail BEFORE INSERT ON lp_inquiry_effective_price FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled refresh persistence failure'");
    try {
      assertThatThrownBy(() -> db.publishFinance(month, "CU_100000"))
          .hasMessageContaining("controlled refresh persistence failure");
    } finally {
      db.jdbc.execute("DROP TRIGGER inquiry_test_fail");
    }
    assertThat(count("lp_factor_upload_batch")).isEqualTo(before);
    assertThat(linked(db.prices(month)).price()).isEqualByComparingTo(amount);
  }

  static InquiryPriceService.Current linked(List<InquiryPriceService.Current> values) {
    return values.stream()
        .filter(v -> v.source().rule().priceType().equals("联动价"))
        .findFirst()
        .orElseThrow();
  }

  static InquiryPriceService.Current fixed(List<InquiryPriceService.Current> values) {
    return values.stream()
        .filter(v -> v.source().rule().priceType().equals("固定价"))
        .findFirst()
        .orElseThrow();
  }

  static long count(String table) {
    return Objects.requireNonNull(
        db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
  }
}
