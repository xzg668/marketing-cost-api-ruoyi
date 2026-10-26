package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.PlatformTransactionManager;

/** Failure classification only; real pool saturation and concurrency have separate MySQL tests. */
class MonthlyReadFailureTest {
  final MonthlyPriceStorage storage = mock(MonthlyPriceStorage.class);
  final MonthlyPriceCandidateResolver sources = mock(MonthlyPriceCandidateResolver.class);
  final MonthlyMaterialPriceService prices = new MonthlyMaterialPriceService(storage,
      mock(PlatformTransactionManager.class), new MonthlyPriceDocumentCodec(new ObjectMapper()), sources);
  final MonthlyPriceRequest request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 10),
      "COMMERCIAL", "M13-READ", null, "件", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), BigDecimal.ONE);
  final Context context = new Context("COMMERCIAL", "M13-READ", 1L, "TOP", YearMonth.of(2026, 10),
      LocalDateTime.of(2026, 10, 23, 0, 0), "MPP13", "210");

  RuntimeException unavailable() {
    return new org.mybatis.spring.MyBatisSystemException(new org.apache.ibatis.exceptions.PersistenceException(
        new CannotGetJdbcConnectionException("Controlled pool exhaustion", new SQLTransientConnectionException("pool unavailable"))));
  }
  @Test void lookupAndHistoricalIdReadClassifyNestedPoolFailures() {
    when(storage.readFamilies(any())).thenThrow(unavailable());
    when(storage.readByIds(anyString(), any())).thenThrow(unavailable());
    for (Runnable action : List.<Runnable>of(() -> prices.lookup(Set.of(request)), () -> prices.readByIds("COMMERCIAL", Set.of(1L))))
      assertThatThrownBy(action::run).isInstanceOfSatisfying(MonthlyPriceProblem.class,
          problem -> assertThat(problem.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.TRANSIENT_CONCURRENCY_FAILURE));
  }
  @Test void currentSourceAndScrapDependencyReadsUseTheSameRetryClassification() {
    when(storage.readFamilies(any())).thenReturn(List.of());
    when(sources.resolve(any(), any(), any())).thenThrow(unavailable());
    when(sources.resolveScrap(any(), any(), any())).thenThrow(unavailable());
    for (Runnable action : List.<Runnable>of(() -> prices.resolveCandidates(context, Set.of(request)),
        () -> prices.resolveScrapCandidates(context, Set.of(request))))
      assertThatThrownBy(action::run).isInstanceOfSatisfying(MonthlyPriceProblem.class,
          problem -> assertThat(problem.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.TRANSIENT_CONCURRENCY_FAILURE));
  }
  @Test void invalidSqlAndExplicitBusinessFailuresAreNotTurnedIntoRetryableConflicts() {
    var invalidSql = new org.springframework.jdbc.BadSqlGrammarException("read", "invalid statement",
        new SQLException("missing column", "42S22", 1054));
    when(storage.readFamilies(any())).thenThrow(invalidSql);
    assertThatThrownBy(() -> prices.lookup(Set.of(request))).isSameAs(invalidSql);
    var domain = new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.EVIDENCE_UNAVAILABLE, "Incomplete immutable evidence");
    doThrow(domain).when(storage).readFamilies(any());
    assertThatThrownBy(() -> prices.resolveCandidates(context, Set.of(request))).isSameAs(domain);
  }
}
