package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

class TechnicalDataPendingRefreshTest {
  private final TechnicalDataPendingState state = mock(TechnicalDataPendingState.class);
  private final TechnicalDataQuoteSourceReader sources = mock(TechnicalDataQuoteSourceReader.class);
  private final TechnicalDataSharedModuleQuery shared = mock(TechnicalDataSharedModuleQuery.class);
  private final TechnicalDataPendingRefresh refresh = new TechnicalDataPendingRefresh(state, sources, shared);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(TechnicalDataPendingRefresh.class);

  @BeforeEach void attach() { logs.start(); logger.addAppender(logs); }
  @AfterEach void clear() { logger.detachAppender(logs); logs.stop(); SecurityContextHolder.clearContext(); }

  @Test void unverifiedModuleRetainsSavedStateAndLogsItsRealReasonWithoutInventingAnException() {
    var original = response(9L, TechnicalDataAvailability.MISSING, true, "SALARY_MISSING", "原缺工资");
    var work = new TechnicalDataPendingState.Work(10L, 9L, "2026-10", "COMMERCIAL", "saved", "token", original);
    when(state.waiting(16)).thenReturn(List.of(work));
    when(sources.preview(9L, "2026-10")).thenReturn(response(9L, TechnicalDataAvailability.ERROR, false,
        "SALARY_SOURCE_READ_FAILED", "工资来源查询失败，请核对来源"));
    var before = SecurityContextHolder.getContext();
    assertThat(refresh.runBatch()).isZero();
    assertThat(SecurityContextHolder.getContext()).isSameAs(before);
    verify(state).retry(work);
    verify(state, never()).complete(any(), any());
    assertThat(logs.list).singleElement().satisfies(event -> {
      assertThat(event.getFormattedMessage()).contains("item=9", "2026-10", "SALARY", "SALARY_SOURCE_READ_FAILED", "工资来源查询失败");
      assertThat(event.getThrowableProxy()).isNull();
    });
    assertThat(work.original()).isSameAs(original);
  }

  @Test void unavailableProductDoesNotPreventAnotherProductsRefresh() {
    var first = new TechnicalDataPendingState.Work(10L, 9L, "2026-10", "COMMERCIAL", "saved", "a",
        response(9L, TechnicalDataAvailability.MISSING, true, "SALARY_MISSING", "原缺工资"));
    var second = new TechnicalDataPendingState.Work(11L, 8L, "2026-10", "COMMERCIAL", "saved", "b",
        response(8L, TechnicalDataAvailability.MISSING, true, "SALARY_MISSING", "原缺工资"));
    when(state.waiting(16)).thenReturn(List.of(first, second));
    when(sources.preview(9L, "2026-10")).thenReturn(response(9L, TechnicalDataAvailability.ERROR, false, "SALARY_QUERY_FAILED", "查询失败"));
    when(sources.preview(8L, "2026-10")).thenReturn(response(8L, TechnicalDataAvailability.AVAILABLE, false, "SALARY_AVAILABLE", "工资已齐"));
    when(shared.current(any(), any(), any())).thenReturn(List.of());
    when(state.complete(eq(second), any())).thenReturn(true);
    assertThat(refresh.runBatch()).isEqualTo(1);
    verify(state).retry(first);
    verify(state, never()).complete(eq(first), any());
    verify(state).complete(eq(second), argThat(check -> check.modules().stream().noneMatch(TechnicalDataModuleRequirement::required)));
  }

  @Test void unexpectedFailureKeepsOriginalExceptionAndRetryEvidence() {
    var work = new TechnicalDataPendingState.Work(10L, 9L, "2026-10", "COMMERCIAL", "saved", "token",
        response(9L, TechnicalDataAvailability.MISSING, true, "SALARY_MISSING", "原缺工资"));
    when(state.waiting(16)).thenReturn(List.of(work));
    when(sources.preview(9L, "2026-10")).thenThrow(new IllegalStateException("实际数据库错误"));
    assertThat(refresh.runBatch()).isZero();
    verify(state).retry(work);
    assertThat(logs.list).singleElement().satisfies(event -> {
      assertThat(event.getThrowableProxy()).isNotNull();
      assertThat(event.getThrowableProxy().getMessage()).isEqualTo("实际数据库错误");
    });
  }

  private TechnicalDataSourceCheckResponse response(long item, TechnicalDataAvailability availability,
      boolean required, String code, String reason) {
    var time = LocalDateTime.now();
    return new TechnicalDataSourceCheckResponse(item, "2026-10", "saved", time, List.of(
        new TechnicalDataModuleRequirement("SALARY", required, code, reason, availability, null, time)), null, List.of());
  }
}
