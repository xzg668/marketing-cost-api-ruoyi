package com.sanhua.marketingcost.service.costing;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OemProductConfigStore {
  public static final String RATE_NOTE = "（OEM）";
  private final JdbcTemplate jdbc;

  public OemProductConfigStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Entry(long id, long revision, String productCode, String productModel,
      String productSpec, String productName, BigDecimal oemRate) {}

  public record QuoteOption(boolean enabled, Long configId, BigDecimal rate) {
    public static final QuoteOption ORDINARY = new QuoteOption(false, null, null);
  }

  public static final class MissingRate extends IllegalArgumentException {
    public MissingRate(String code) {
      super("产品 " + code + " 缺少OEM费率，请在OEM产品配置表或三项费用率表补齐");
    }
  }

  public QuoteOption quoteOption(Long itemId, String unit, String productCode) {
    if (itemId == null) return QuoteOption.ORDINARY;
    return jdbc.query("""
        SELECT c.id,c.oem_rate FROM lp_quote_item_oem o
        LEFT JOIN lp_oem_product_config c ON c.business_unit_type=? AND c.product_code=?
        WHERE o.oa_form_item_id=?
        """, (row, index) -> new QuoteOption(true, row.getObject(1, Long.class), row.getBigDecimal(2)),
        unit, productCode, itemId).stream().findFirst().orElse(QuoteOption.ORDINARY);
  }

  public Set<Long> quoteItems(long formId) {
    return Set.copyOf(jdbc.queryForList(
        "SELECT oa_form_item_id FROM lp_quote_item_oem WHERE oa_form_id=?", Long.class, formId));
  }

  public void setQuoteItem(long formId, long itemId, boolean enabled) {
    if (enabled) {
      jdbc.update("INSERT IGNORE INTO lp_quote_item_oem (oa_form_item_id,oa_form_id) VALUES (?,?)", itemId, formId);
    } else {
      jdbc.update("DELETE FROM lp_quote_item_oem WHERE oa_form_id=? AND oa_form_item_id=?", formId, itemId);
    }
  }

  public List<Entry> list(String unit) {
    return jdbc.query("""
        SELECT id, revision, product_code, product_model, product_spec, product_name, oem_rate
        FROM lp_oem_product_config WHERE business_unit_type=? ORDER BY product_code
        """, OemProductConfigStore::row, unit);
  }

  public void insert(String unit, Entry entry) {
    jdbc.update("""
        INSERT INTO lp_oem_product_config(business_unit_type, product_code, product_model, product_spec, product_name, oem_rate)
        VALUES(?, ?, ?, ?, ?, ?)
        """, unit, entry.productCode(), entry.productModel(), entry.productSpec(), entry.productName(), entry.oemRate());
  }

  public void update(String unit, Entry entry) {
    requireChanged(jdbc.update("""
        UPDATE lp_oem_product_config SET product_code=?, product_model=?, product_spec=?, product_name=?, oem_rate=?, revision=revision+1
        WHERE business_unit_type=? AND id=? AND revision=?
        """, entry.productCode(), entry.productModel(), entry.productSpec(), entry.productName(),
        entry.oemRate(), unit, entry.id(), entry.revision()));
  }

  public void delete(String unit, long id, long revision) {
    requireChanged(jdbc.update("DELETE FROM lp_oem_product_config WHERE business_unit_type=? AND id=? AND revision=?",
        unit, id, revision));
  }

  private static void requireChanged(int changed) {
    if (changed != 1) throw new IllegalArgumentException("OEM产品配置已变化，请刷新后重试");
  }

  private static Entry row(ResultSet row, int index) throws SQLException {
    return new Entry(row.getLong("id"), row.getLong("revision"), row.getString("product_code"),
        row.getString("product_model"), row.getString("product_spec"), row.getString("product_name"),
        row.getBigDecimal("oem_rate"));
  }
}
