package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.config.MonthlyRepriceProperties;
import com.sanhua.marketingcost.mapper.QuotePricingPeriodMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfirmedQuotePricingPeriodTest {
  private final QuotePricingPeriodMapper mapper = mock(QuotePricingPeriodMapper.class);
  private final MonthlyRepriceProperties properties = new MonthlyRepriceProperties();
  private final ConfirmedQuotePricingPeriod periods = new ConfirmedQuotePricingPeriod(mapper, properties);

  @Test void calendarRolloverDoesNotAdvanceExistingOctoberQuotation() {
    when(mapper.existingQuotationMonth("COMMERCIAL")).thenReturn("2026-10");
    assertThat(periods.requireActiveMonth(null, "COMMERCIAL")).isEqualTo("2026-10");
    assertThat(periods.requireActiveMonth("2026-10", "COMMERCIAL")).isEqualTo("2026-10");
    assertThatThrownBy(() -> periods.requireActiveMonth("2026-11", "COMMERCIAL"))
        .hasMessageContaining("当前生效核算月份为 2026-10");
  }

  @Test void confirmationSwitchesEveryCallerWithoutRestartOrStaleCache() {
    when(mapper.confirmedMonth("COMMERCIAL")).thenReturn("2026-10", "2026-11", "2026-11");
    assertThat(periods.currentMonth("COMMERCIAL")).isEqualTo("2026-10");
    assertThat(periods.requireActiveMonth(null, "COMMERCIAL")).isEqualTo("2026-11");
    assertThatThrownBy(() -> periods.requireActiveMonth("2026-10", "COMMERCIAL"))
        .hasMessageContaining("请刷新");
    verify(mapper, never()).existingQuotationMonth(any());
  }

  @Test void unitsSwitchIndependentlyAndAnEmptyInstallationUsesItsVerifiedLaunchMonth() {
    properties.setGoLiveMonths(Map.of("HOUSEHOLD", "2026-09"));
    when(mapper.confirmedMonth("COMMERCIAL")).thenReturn("2026-11");
    assertThat(periods.currentMonth("COMMERCIAL")).isEqualTo("2026-11");
    assertThat(periods.currentMonth("HOUSEHOLD")).isEqualTo("2026-09");
  }

  @Test void missingInitializationOrInvalidUnitCannotSilentlyChooseTodaysMonth() {
    assertThatThrownBy(() -> periods.currentMonth("COMMERCIAL")).hasMessageContaining("实际上线月份");
    assertThatThrownBy(() -> periods.currentMonth(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
