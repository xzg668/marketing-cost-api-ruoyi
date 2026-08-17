package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("QCBP-25 协作运维指标契约")
class CollaborationOperationsMetricsTest {
  @Test
  void exposesCountsWaitDurationRatesReuseAndReconciliationAnomalies() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new CollaborationOperationsMetrics(registry, mock(JdbcTemplate.class),
        mock(CollaborationOperationsService.class));

    Set<String> names = registry.getMeters().stream()
        .map(meter -> meter.getId().getName()).collect(Collectors.toSet());

    assertThat(names).contains(
        "quote.collaboration.tasks.open",
        "quote.collaboration.tasks.waiting_finance",
        "quote.collaboration.waiting_finance.max_seconds",
        "quote.collaboration.validation.failure_ratio",
        "quote.collaboration.review.rejection_ratio",
        "quote.collaboration.publication.failure_ratio",
        "quote.collaboration.results.reused",
        "quote.collaboration.reconciliation.anomalies");
  }
}
