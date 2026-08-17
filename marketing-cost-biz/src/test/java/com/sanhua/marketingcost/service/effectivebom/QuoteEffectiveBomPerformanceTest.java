package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** QEB-16 大树基准：不设业务 SLA，只防止发布版本出现秒级以上的明显退化。 */
class QuoteEffectiveBomPerformanceTest {

  private static final int PREVIEW_SAMPLES = 15;
  private static final int CONFIRM_SAMPLES = 7;
  private static final double SAFETY_LIMIT_MILLIS = 10_000D;

  @Test
  void oneThousandNodePreviewReportsP50AndP95() {
    EffectiveBomBuildRequest request = request(1_000);
    QuoteEffectiveBomBuilder builder = builder();
    builder.build(request);
    builder.build(request);

    List<Double> samples = new ArrayList<>();
    for (int index = 0; index < PREVIEW_SAMPLES; index++) {
      long started = System.nanoTime();
      EffectiveBomBuildResult result = builder.build(request);
      samples.add(elapsedMillis(started));
      assertThat(result.blocked()).isFalse();
      assertThat(result.nodes()).hasSize(1_000);
    }

    report("preview", 1_000, samples, 0);
    assertThat(percentile(samples, 0.95)).isLessThan(SAFETY_LIMIT_MILLIS);
  }

  @Test
  void fiveThousandNodeBuildAndConfirmReportsP50AndP95() {
    EffectiveBomBuildRequest request = request(5_000);
    QuoteEffectiveBomBuilder builder = builder();
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();
    QuoteEffectiveBomPersistenceServiceImpl persistence =
        EffectiveBomPersistenceTestSupport.service(repository);
    confirm(builder, persistence, request, 1L);

    List<Double> samples = new ArrayList<>();
    for (int index = 0; index < CONFIRM_SAMPLES; index++) {
      long started = System.nanoTime();
      QuoteEffectiveBomPersistenceResult result =
          confirm(builder, persistence, request, index + 2L);
      samples.add(elapsedMillis(started));
      assertThat(result.nodeCount()).isEqualTo(5_000);
      assertThat(result.reused()).isTrue();
    }

    report("build_confirm_reuse", 5_000, samples, 0);
    assertThat(repository.buildCount()).isEqualTo(1);
    assertThat(repository.insertCalls()).isEqualTo(1);
    assertThat(percentile(samples, 0.95)).isLessThan(SAFETY_LIMIT_MILLIS);
  }

  @Test
  void oneHundredProductsWithOneThousandNodesEachStayWithinSafetyLimit() {
    EffectiveBomBuildRequest request = request(1_000);
    QuoteEffectiveBomBuilder builder = builder();
    builder.build(request);
    long started = System.nanoTime();
    int totalNodes = 0;
    for (int product = 0; product < 100; product++) {
      EffectiveBomBuildResult result = builder.build(request);
      assertThat(result.blocked()).isFalse();
      totalNodes += result.nodes().size();
    }
    double elapsed = elapsedMillis(started);
    System.out.printf(Locale.ROOT,
        "QCBP26_PERF operation=hundred_product_bom_build products=100 nodes_per_product=1000 total_nodes=%d elapsed_ms=%.3f%n",
        totalNodes, elapsed);
    assertThat(totalNodes).isEqualTo(100_000);
    assertThat(elapsed).isLessThan(SAFETY_LIMIT_MILLIS);
  }

  private static QuoteEffectiveBomPersistenceResult confirm(
      QuoteEffectiveBomBuilder builder,
      QuoteEffectiveBomPersistenceServiceImpl persistence,
      EffectiveBomBuildRequest request,
      long snapshotId) {
    EffectiveBomBuildResult built = builder.build(request);
    assertThat(built.blocked()).isFalse();
    EffectiveBomVariantInput variant =
        new EffectiveBomVariantInput(
            "2026-08", "RAW-PERF-1", "210", "P", "BOX", Map.of(), built);
    return persistence.persistConfirmed(
        new QuoteEffectiveBomPersistenceRequest(snapshotId, 9527L, Map.of(), variant));
  }

  private static EffectiveBomBuildRequest request(int nodeCount) {
    List<BomRawHierarchy> rows = new ArrayList<>(nodeCount);
    Map<String, EffectiveBomShapeDecision> decisions = new LinkedHashMap<>(nodeCount);
    rows.add(node(1L, "P", "P", 0, "/P/", "1", "制造件"));
    decisions.put(
        "P",
        EffectiveBomShapeDecision.u9(
            "P", "制造件", QuoteMaterialShape.MANUFACTURE));
    for (int index = 1; index < nodeCount; index++) {
      String materialCode = String.format(Locale.ROOT, "M-%05d", index);
      rows.add(
          node(
              index + 1L,
              materialCode,
              "P",
              1,
              "/P/" + materialCode + "/",
              "1",
              "采购件"));
      decisions.put(
          materialCode,
          EffectiveBomShapeDecision.u9(
              materialCode, "采购件", QuoteMaterialShape.PURCHASE));
    }
    return new EffectiveBomBuildRequest(rows, List.of(), Map.of(), decisions, 128);
  }

  private static double elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000D;
  }

  private static double percentile(List<Double> values, double percentile) {
    List<Double> sorted = values.stream().sorted().toList();
    int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
    return sorted.get(index);
  }

  private static void report(
      String operation, int nodeCount, List<Double> samples, int sqlCount) {
    System.out.printf(
        Locale.ROOT,
        "QEB16_PERF operation=%s nodes=%d samples=%d p50_ms=%.3f p95_ms=%.3f sql_count=%d%n",
        operation,
        nodeCount,
        samples.size(),
        percentile(samples, 0.50),
        percentile(samples, 0.95),
        sqlCount);
  }
}
