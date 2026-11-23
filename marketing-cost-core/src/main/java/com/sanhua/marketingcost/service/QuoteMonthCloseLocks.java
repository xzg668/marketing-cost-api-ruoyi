package com.sanhua.marketingcost.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Shared by ordinary quotation admission, publication and the independent worker. */
@Service
public class QuoteMonthCloseLocks {
  public static final String COMMERCIAL = "COMMERCIAL";
  public static final String CLOSED_MESSAGE = "本产品当月成本已结账，不能重新核算";

  public enum Mode {
    LEGACY, PREVIEW, ENABLED
  }

  private final JdbcTemplate jdbc;
  private final Mode mode;

  public QuoteMonthCloseLocks(JdbcTemplate jdbc,
      @Value("${quote.month-close.mode:LEGACY}") String mode) {
    this.jdbc = jdbc;
    this.mode = Mode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
  }

  public boolean hasStorage() {
    return mode != Mode.LEGACY;
  }

  public boolean permitsChanges() {
    return mode == Mode.ENABLED;
  }

  public Map<Long, Long> forOrder(String unit, String month, String oaNo) {
    if (!hasStorage() || !COMMERCIAL.equals(unit)) return Map.of();
    return jdbc.query("""
        SELECT i.oa_form_item_id, i.cost_version_id
        FROM lp_quote_month_close c JOIN lp_quote_month_close_item i ON i.close_id=c.id
        WHERE c.active_period_month=? AND i.oa_no=?
        """, (rs, row) -> Map.entry(rs.getLong(1), rs.getLong(2)), month, oaNo)
        .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public Long version(String unit, String month, Long itemId) {
    if (!hasStorage() || !COMMERCIAL.equals(unit) || month == null || itemId == null) return null;
    List<Long> result = jdbc.queryForList("""
        SELECT i.cost_version_id
        FROM lp_quote_month_close c JOIN lp_quote_month_close_item i ON i.close_id=c.id
        WHERE c.active_period_month=? AND i.oa_form_item_id=?
        """, Long.class, month, itemId);
    return result.isEmpty() ? null : result.getFirst();
  }

  public void requireOpen(String unit, String month, Long itemId) {
    if (version(unit, month, itemId) != null) throw new IllegalStateException(CLOSED_MESSAGE);
  }
}
