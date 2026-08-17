package com.sanhua.marketingcost.service.collaboration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 低基数生产指标，不写业务数据。 */
@Component
public class CollaborationOperationsMetrics {
  public CollaborationOperationsMetrics(
      MeterRegistry registry, JdbcTemplate jdbc, CollaborationOperationsService operationsService) {
    gauge(registry, jdbc, "quote.collaboration.tasks.open",
        "SELECT COUNT(*) FROM lp_quote_collaboration_product_task WHERE active_flag=1");
    gauge(registry, jdbc, "quote.collaboration.tasks.waiting_finance",
        "SELECT COUNT(*) FROM lp_quote_collaboration_product_task WHERE task_status='WAIT_FINANCE'");
    gauge(registry, jdbc, "quote.collaboration.validation.failed",
        "SELECT COUNT(*) FROM lp_quote_collaboration_product_task WHERE last_validation_status='FAILED'");
    gauge(registry, jdbc, "quote.collaboration.reviews.rejected",
        "SELECT COUNT(*) FROM lp_quote_collaboration_review WHERE review_status='REJECTED'");
    gauge(registry, jdbc, "quote.collaboration.publication.failed",
        "SELECT COUNT(*) FROM lp_quote_collaboration_review WHERE review_status='FAILED'");
    gauge(registry, jdbc, "quote.collaboration.results.reused",
        "SELECT COUNT(*) FROM lp_quote_collaboration_quote_link WHERE link_type='APPROVED_RESULT_REUSE'");
    Gauge.builder("quote.collaboration.waiting_finance.max_seconds",
        () -> safeDouble(jdbc, """
            SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,updated_at,NOW())),0)
            FROM lp_quote_collaboration_product_task WHERE task_status='WAIT_FINANCE'
            """)).register(registry);
    Gauge.builder("quote.collaboration.validation.failure_ratio",
        () -> safeRatio(jdbc, """
            SELECT COALESCE(SUM(last_validation_status='FAILED'),0),COUNT(*)
            FROM lp_quote_collaboration_product_task WHERE active_flag=1
            """)).register(registry);
    Gauge.builder("quote.collaboration.review.rejection_ratio",
        () -> safeRatio(jdbc, """
            SELECT COALESCE(SUM(review_status='REJECTED'),0),COUNT(*)
            FROM lp_quote_collaboration_review
            """)).register(registry);
    Gauge.builder("quote.collaboration.publication.failure_ratio",
        () -> safeRatio(jdbc, """
            SELECT COALESCE(SUM(review_status='FAILED'),0),
                   COALESCE(SUM(review_status IN ('FAILED','EFFECTIVE')),0)
            FROM lp_quote_collaboration_review
            """)).register(registry);
    Gauge.builder("quote.collaboration.reconciliation.anomalies",
        () -> safeReconciliation(operationsService)).register(registry);
  }

  private static void gauge(MeterRegistry registry, JdbcTemplate jdbc, String name, String sql) {
    Gauge.builder(name, () -> safeCount(jdbc, sql)).register(registry);
  }

  private static int safeCount(JdbcTemplate jdbc, String sql) {
    try {
      Integer value = jdbc.queryForObject(sql, Integer.class);
      return value == null ? 0 : value;
    } catch (RuntimeException ignored) {
      return -1;
    }
  }

  private static double safeDouble(JdbcTemplate jdbc, String sql) {
    try {
      Number value = jdbc.queryForObject(sql, Number.class);
      return value == null ? 0D : value.doubleValue();
    } catch (RuntimeException ignored) {
      return -1D;
    }
  }

  private static double safeRatio(JdbcTemplate jdbc, String sql) {
    try {
      return jdbc.query(sql, rs -> {
        if (!rs.next()) return 0D;
        double numerator = rs.getDouble(1);
        double denominator = rs.getDouble(2);
        return denominator == 0D ? 0D : numerator / denominator;
      });
    } catch (RuntimeException ignored) {
      return -1D;
    }
  }

  private static int safeReconciliation(CollaborationOperationsService service) {
    try {
      return service.reconcile().total();
    } catch (RuntimeException ignored) {
      return -1;
    }
  }
}
