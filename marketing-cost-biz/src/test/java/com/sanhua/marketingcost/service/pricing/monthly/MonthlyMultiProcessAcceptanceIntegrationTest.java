package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** Separate production application JVMs, 32 clients and genuine independent MySQL connections. */
@Tag("slow")
public class MonthlyMultiProcessAcceptanceIntegrationTest {
  static MonthlyLocalAcceptance fixture;
  static String run;
  @BeforeAll static void start() throws Exception {
    fixture = new MonthlyLocalAcceptance(); run = "M13-R-" + UUID.randomUUID().toString().substring(0, 8);
  }
  @AfterAll static void stop() { if (fixture != null) fixture.close(); }

  @Test void twoProcessesAndThirtyTwoClientsConvergeForFixedHouseholdAndBothMixedWinners() throws Exception {
    for (String scenario : List.of("FIXED", "HOUSEHOLD", "NORMAL_WINS", "HOUSEHOLD_WINS")) {
      String identity = run + "-" + scenario;
      var materials = new ArrayList<String>();
      for (int i = 0; i < 100; i++) materials.add(identity + "-" + i);
      fixture.fixed(materials);
      if (scenario.equals("HOUSEHOLD")) fixture.publish(materials, "20.9853");
      Path directory = fixture.reports.resolve(identity); Files.createDirectory(directory);
      var first = launch(directory, "first", materials);
      Process second = null;
      try {
        await(directory.resolve("first-ready.json"), first);
        if (scenario.endsWith("WINS")) fixture.publish(materials, "20.9853");
        second = launch(directory, "second", materials, scenario.equals("HOUSEHOLD") ? "220" : "210");
        await(directory.resolve("second-ready.json"), second);
        String winner = scenario.equals("HOUSEHOLD_WINS") ? "second" : "first";
        Files.createFile(directory.resolve(winner + "-go"));
        if (scenario.endsWith("WINS"))
          await(directory.resolve(winner + "-first-success.json"), winner.equals("first") ? first : second);
        Files.createFile(directory.resolve((winner.equals("first") ? "second" : "first") + "-go"));
        var readSnapshots=new ArrayList<Long>();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(first.isAlive() || second.isAlive()) {
          long rows=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE business_unit_type='COMMERCIAL' AND pricing_month='2026-10' AND material_code LIKE ?",Long.class,identity+"-%");
          assertThat(rows).as("Readers see an entire committed hundred-fact batch").isIn(0L,100L);
          readSnapshots.add(rows);
          if(System.nanoTime()>deadline)throw new IllegalStateException("Child processes exceeded their deadline");
          Thread.sleep(25);
        }
        assertThat(readSnapshots).isNotEmpty();
        assertThat(first.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(second.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(first.exitValue()).isZero(); assertThat(second.exitValue()).isZero();
        var outcomes = new ArrayList<JsonNode>();
        for (String side : List.of("first", "second")) {
          var result = fixture.json.readTree(Files.readString(directory.resolve(side + "-result.json")));
          assertThat(result.path("pid").asLong()).isNotEqualTo(ProcessHandle.current().pid());
          assertThat(result.path("clients").size()).isEqualTo(16);
          result.path("clients").forEach(outcomes::add);
        }
        var stored = fixture.prices.lookup(materials.stream().map(code -> MonthlyLocalAcceptance.request(code, "210")).toList());
        assertThat(stored.missing()).isEmpty(); assertThat(stored.values()).hasSize(100);
        assertThat(stored.values().values()).allSatisfy(value -> assertThat(value.fact().type())
            .isEqualTo(scenario.equals("HOUSEHOLD") || scenario.equals("HOUSEHOLD_WINS") ? PricingType.HOUSEHOLD_INQUIRY : PricingType.NORMAL));
        var authoritative = new TreeMap<String, Long>();
        stored.values().forEach((request, selection) -> authoritative.put(request.family().materialCode(), selection.monthlyPriceId()));
        int successes = 0;
        for (var outcome : outcomes) {
          assertThat(outcome.path("elapsedMs").asDouble()).isLessThanOrEqualTo(10000);
          if (outcome.path("status").asText().equals("SUCCESS")) {
            successes++;
            var ids = fixture.json.convertValue(outcome.path("ids"), new com.fasterxml.jackson.core.type.TypeReference<TreeMap<String, Long>>() {});
            assertThat(ids).isEqualTo(authoritative);
          } else assertThat(outcome.path("reason").asText()).isIn("TRANSIENT_CONCURRENCY_FAILURE", "REPLAN_REQUIRED");
        }
        assertThat(successes).isPositive();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code LIKE ?", Long.class, identity + "%")).isEqualTo(100);
        fixture.write(identity + "-verification.json", Map.of("scenario", scenario, "processes", 2,
            "clients", 32, "uniqueIdentities", 100, "successes", successes, "boundedConflicts", 32 - successes,
            "authoritativeIds", authoritative, "outcomes", outcomes,"atomicReadWriteInterleavingSnapshots",readSnapshots,
            "consumerOrganizations",scenario.equals("HOUSEHOLD")?List.of("210","220"):List.of("210")));
      } finally {
        if (first.isAlive()) first.destroyForcibly();
        if (second != null && second.isAlive()) second.destroyForcibly();
      }
    }
  }

  @Test void actualCommitResponseLossAndFailedInsertRecoverWithoutPartialFacts() throws Exception {
    var materials = List.of(run + "-LOST-A", run + "-LOST-B"); fixture.fixed(materials);
    var context = fixture.context(run + "-lost");
    var requests = materials.stream().map(code -> MonthlyLocalAcceptance.request(code, "210"))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var candidates = fixture.prices.resolveCandidates(context, requests);
    var selections = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    candidates.values().forEach((request, value) -> selections.put(request, value.selection()));
    MonthlyAcceptanceSqlDriver.loseCommitAcknowledgement.set(true);
    var saved = fixture.prices.adopt(context, MonthlyLocalAcceptance.plan(selections));
    assertThat(MonthlyAcceptanceSqlDriver.loseCommitAcknowledgement.get()).isNull();
    assertThat(saved.plan().selections()).isEqualTo(fixture.prices.lookup(requests).values());
    assertThat(fixture.prices.adopt(context, saved.plan()).plan().selections()).isEqualTo(saved.plan().selections());
    var failures = List.of(run + "-ROLLBACK-A", run + "-ROLLBACK-B"); fixture.fixed(failures);
    var failedRequests = failures.stream().map(code -> MonthlyLocalAcceptance.request(code, "210"))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var selected = fixture.prices.resolveCandidates(context, failedRequests);
    var input = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    selected.values().forEach((request, value) -> input.put(request, value.selection()));
    var failedPlan=MonthlyLocalAcceptance.plan(input);
    String failingMaterial=new ArrayList<>(failedPlan.selections().keySet()).getLast().family().materialCode();
    fixture.jdbc.update("INSERT INTO mpp13_fault_control(material_code) VALUES(?)", failingMaterial);
    try {
      MonthlyAcceptanceSqlDriver.reset();
      assertThatThrownBy(() -> fixture.prices.adopt(context, failedPlan)).isInstanceOf(MonthlyPriceProblem.class);
      var executed=(Map<String,Long>)MonthlyAcceptanceSqlDriver.snapshot().get("statements");
      assertThat(executed.entrySet().stream().filter(e->e.getKey().startsWith("INSERT INTO lp_material_monthly_price"))
          .mapToLong(Map.Entry::getValue).sum()).isEqualTo(2);
      assertThat(fixture.prices.lookup(failedRequests).missing()).containsExactlyInAnyOrderElementsOf(failedRequests);
    } finally { fixture.jdbc.update("DELETE FROM mpp13_fault_control WHERE material_code=?", failingMaterial); }
    var recovered = fixture.prices.adopt(context, failedPlan);
    assertThat(recovered.plan().selections()).hasSize(2);
    fixture.write(run + "-fault-recovery.json", Map.of("actualCommitAcknowledgementLost", true,
        "sameIdsAfterRetry", true, "insertAttemptsBeforeRollback",2,"failedBatchNewRows", 0, "recoveredRows", 2));
  }

  @Test void exhaustedMonthlyPoolDuringReplanningReturnsABoundedDomainConflict() throws Exception {
    var database = fixture.app.getBean(MonthlyPriceDatabase.class);
    var held = new ArrayList<java.sql.Connection>(); var attempts = new AtomicInteger();
    var request = MonthlyLocalAcceptance.request(run + "-POOL", "210");
    long start = System.nanoTime();
    try {
      for (int i = 0; i < 8; i++) held.add(database.pool().getConnection());
      assertThatThrownBy(() -> new MonthlyAdoptionExecutor(fixture.prices).execute(fixture.context(run), remaining -> {
        attempts.incrementAndGet(); fixture.prices.resolveCandidates(fixture.context(run), Set.of(request));
        return MonthlyLocalAcceptance.plan(Map.of());
      })).isInstanceOfSatisfying(MonthlyPriceProblem.class,
          problem -> assertThat(problem.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.TRANSIENT_CONCURRENCY_FAILURE));
      assertThat(attempts.get()).isEqualTo(3);
      assertThat((System.nanoTime() - start) / 1e6).isLessThan(3000);
    } finally { for (var connection : held) connection.close(); }
    assertThat(fixture.prices.lookup(Set.of(request)).missing()).containsExactly(request);
    fixture.write(run + "-pool-exhaustion.json", Map.of("attempts", attempts.get(), "newMonthlyRows", 0,
        "elapsedMs", (System.nanoTime() - start) / 1e6, "reason", "TRANSIENT_CONCURRENCY_FAILURE"));
  }

  @Test void genuineMysqlDeadlockIsRetriedWithinTheOriginalBudget() throws Exception {
    var materials = List.of(run + "-DEADLOCK-A", run + "-DEADLOCK-B"); fixture.fixed(materials);
    var context = fixture.context(run + "-deadlock");
    var requests = materials.stream().map(code -> MonthlyLocalAcceptance.request(code, "210"))
        .sorted(Comparator.comparing(MonthlyPriceRequest::family)).toList();
    var candidates = fixture.prices.resolveCandidates(context, new LinkedHashSet<>(requests));
    var initial = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    candidates.values().forEach((request, value) -> initial.put(request, value.selection()));
    var saved = fixture.prices.adopt(context, MonthlyLocalAcceptance.plan(initial));
    var ordered = requests.stream().sorted(Comparator.comparing(request -> request.family())).toList();
    String firstHash = MonthlyPriceDocumentCodec.sha256(ordered.getFirst().family().canonical());
    String lastHash = MonthlyPriceDocumentCodec.sha256(ordered.getLast().family().canonical());
    // The module sorts by the canonical family, not hash. A separate heavier transaction takes the reverse lock order.
    var raw = new org.springframework.jdbc.datasource.DriverManagerDataSource(fixture.settings.path("url").asText(),
        fixture.settings.path("username").asText(), fixture.settings.path("password").asText());
    var firstLocked = new CountDownLatch(1); var releaseFirst = new CountDownLatch(1); var attempts = new AtomicInteger();
    long priorDeadlocks = MonthlyAcceptanceSqlDriver.actualDeadlocks.get();
    try (var blocker = raw.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      // Give the blocker more undo work so InnoDB selects the real module transaction as victim.
      try (var weights = blocker.createStatement()) {
        weights.execute("CREATE TEMPORARY TABLE mpp13_deadlock_weight(id INT PRIMARY KEY,v INT) ENGINE=InnoDB");
        for (int i = 0; i < 20; i++) weights.executeUpdate("INSERT INTO mpp13_deadlock_weight VALUES(" + i + ",1)");
      }
      try (var lock = blocker.prepareStatement("SELECT id FROM lp_material_monthly_price WHERE family_hash=? FOR UPDATE")) {
        lock.setString(1, lastHash); lock.executeQuery().close();
      }
      long start = System.nanoTime();
      var result = pool.submit(() -> {
        MonthlyAcceptanceSqlDriver.afterFirstFamilyLock.set(() -> {
          firstLocked.countDown(); try { if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lock handoff timeout"); }
          catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        });
        return new MonthlyAdoptionExecutor(fixture.prices).execute(context, remaining -> {
          attempts.incrementAndGet(); return saved.plan();
        });
      });
      assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
      releaseFirst.countDown();
      try (var lock = blocker.prepareStatement("SELECT id FROM lp_material_monthly_price WHERE family_hash=? FOR UPDATE")) {
        lock.setString(1, firstHash); lock.executeQuery().close();
      }
      blocker.commit();
      assertThat(result.get(10, TimeUnit.SECONDS).plan().selections()).isEqualTo(saved.plan().selections());
      double elapsed = (System.nanoTime() - start) / 1e6;
      assertThat(MonthlyAcceptanceSqlDriver.actualDeadlocks.get() - priorDeadlocks).isPositive();
      assertThat(attempts.get()).isBetween(2, 3); assertThat(elapsed).isLessThanOrEqualTo(10000);
      fixture.write(run + "-deadlock.json", Map.of("mysqlErrorCode", 1213, "observedDeadlocks",
          MonthlyAcceptanceSqlDriver.actualDeadlocks.get() - priorDeadlocks, "attempts", attempts.get(), "elapsedMs", elapsed));
    } finally { releaseFirst.countDown(); }
  }

  static Process launch(Path directory, String side, List<String> materials) throws Exception {
    return launch(directory,side,materials,"210");
  }
  static Process launch(Path directory, String side, List<String> materials,String organization) throws Exception {
    var input = directory.resolve(side + "-input.json");
    fixture.json.writeValue(input.toFile(), Map.of("materials", materials, "directory", directory.toString(), "side", side,"organization",organization));
    String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xmx768m", "-cp", classpath,
        "-Dmpp13.private-config=" + System.getProperty("mpp13.private-config", "/tmp/mpp13-performance-private.json"),
        MonthlyMultiProcessAcceptanceIntegrationTest.class.getName(), input.toString())
        .redirectErrorStream(true).redirectOutput(directory.resolve(side + "-process.log").toFile()).start();
  }
  static void await(Path path, Process process) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    while (!Files.exists(path)) {
      if (!process.isAlive()) throw new IllegalStateException("MPP13 child exited before " + path.getFileName());
      if (System.nanoTime() > deadline) throw new IllegalStateException("MPP13 child timeout: " + path.getFileName());
      Thread.sleep(20);
    }
  }

  public static void main(String[] args) throws Exception {
    var json = new ObjectMapper().findAndRegisterModules(); var input = json.readTree(Files.readString(Path.of(args[0])));
    Path directory = Path.of(input.path("directory").asText()); String side = input.path("side").asText();
    String organization=input.path("organization").asText("210");
    var materials = new ArrayList<String>(); input.path("materials").forEach(value -> materials.add(value.asText()));
    try (var local = new MonthlyLocalAcceptance(); var pool = Executors.newFixedThreadPool(16)) {
      var requests = materials.stream().map(code -> MonthlyLocalAcceptance.request(code, organization))
          .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
      var ready = new CountDownLatch(16); var gate = new CountDownLatch(1);
      var clients = new ArrayList<Future<Map<String, Object>>>();
      for (int client = 0; client < 16; client++) {
        int number = client;
        clients.add(pool.submit(() -> {
          var base=local.context(materials.getFirst() + "-" + side + "-" + number);
          var context = new Context(base.businessUnitType(),base.oaNo(),base.oaFormItemId(),base.productCode(),base.month(),base.priceAsOfTime(),base.actor(),organization);
          var candidates = local.prices.resolveCandidates(context, requests);
          assertThat(candidates.problems()).isEmpty(); assertThat(candidates.values()).hasSize(100);
          var selected = new LinkedHashMap<MonthlyPriceRequest, Selection>();
          candidates.values().forEach((request, value) -> selected.put(request, value.selection()));
          var initial = MonthlyLocalAcceptance.plan(selected);
          ready.countDown(); if (!gate.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timeout");
          long start = System.nanoTime(); var attempt = new AtomicInteger(); var row = new LinkedHashMap<String, Object>();
          try {
            var result = new MonthlyAdoptionExecutor(local.prices).execute(context, remaining -> {
              if (attempt.getAndIncrement() == 0) return initial;
              var current = local.prices.resolveCandidates(context, requests);
              var values = new LinkedHashMap<MonthlyPriceRequest, Selection>();
              current.values().forEach((request, value) -> values.put(request, value.selection()));
              assertThat(current.problems()).isEmpty(); return MonthlyLocalAcceptance.plan(values);
            });
            var ids = new TreeMap<String, Long>();
            result.plan().selections().forEach((request, value) -> ids.put(request.family().materialCode(), value.monthlyPriceId()));
            row.put("status", "SUCCESS"); row.put("ids", ids);
            try { Files.writeString(directory.resolve(side + "-first-success.json"), "{}", StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException expected) { /* Another successful client already signalled. */ }
          } catch (MonthlyPriceProblem conflict) {
            row.put("status", "CONFLICT"); row.put("reason", conflict.reason().name());
          }
          row.put("elapsedMs", (System.nanoTime() - start) / 1e6); row.put("attempts", attempt.get()); return row;
        }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      json.writeValue(directory.resolve(side + "-ready.json").toFile(), Map.of("pid", ProcessHandle.current().pid(), "clients", 16));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (!Files.exists(directory.resolve(side + "-go"))) {
        if (System.nanoTime() > deadline) throw new IllegalStateException("Parent start gate timeout"); Thread.sleep(20);
      }
      gate.countDown(); var results = new ArrayList<Map<String, Object>>();
      for (var client : clients) results.add(client.get(15, TimeUnit.SECONDS));
      json.writeValue(directory.resolve(side + "-result.json").toFile(), Map.of("pid", ProcessHandle.current().pid(), "clients", results));
    }
  }
}
