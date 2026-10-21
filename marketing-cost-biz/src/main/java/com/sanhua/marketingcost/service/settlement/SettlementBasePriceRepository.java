package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 所有查询显式绑定业务单元，绝不读取普通财务因素作为补值。 */
@Repository
public class SettlementBasePriceRepository {
  private static final DataClassRowMapper<SettlementBasePrice> ROW = DataClassRowMapper.newInstance(SettlementBasePrice.class);
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public SettlementBasePriceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  public Optional<SettlementBasePrice> effective(SettlementScope scope) {
    return jdbc.query("SELECT * FROM lp_settlement_base_price WHERE business_unit_type=? AND active=1 "
        + "AND price_month<=? ORDER BY price_month DESC,revision_no DESC LIMIT 1", ROW,
        scope.businessUnitType(), scope.priceMonth()).stream().findFirst();
  }

  public List<SettlementBasePrice> history(SettlementScope scope) {
    return jdbc.query("SELECT * FROM lp_settlement_base_price WHERE business_unit_type=? AND price_month=? "
        + "ORDER BY revision_no DESC", ROW, scope.businessUnitType(), scope.priceMonth());
  }

  public Optional<SettlementBasePrice> latest(SettlementScope scope) {
    return jdbc.query("SELECT * FROM lp_settlement_base_price WHERE business_unit_type=? AND price_month=? "
        + "ORDER BY revision_no DESC LIMIT 1", ROW, scope.businessUnitType(), scope.priceMonth()).stream().findFirst();
  }

  public SettlementBasePrice insert(SettlementScope scope, int revision, BigDecimal copper, BigDecimal zinc,
      String sourceKind, String fileSha256, String reason, String actor) {
    var parameters = new MapSqlParameterSource().addValue("businessUnit", scope.businessUnitType())
        .addValue("month", scope.priceMonth()).addValue("revision", revision).addValue("cu", copper).addValue("zn", zinc)
        .addValue("source", sourceKind).addValue("sha", fileSha256).addValue("reason", reason).addValue("actor", actor);
    var key = new GeneratedKeyHolder();
    named.update("""
        INSERT INTO lp_settlement_base_price(business_unit_type,price_month,revision_no,copper_price,zinc_price,
          source_kind,source_file_sha256,change_reason,created_by)
        VALUES(:businessUnit,:month,:revision,:cu,:zn,:source,:sha,:reason,:actor)
        """, parameters, key, new String[]{"id"});
    if (key.getKey() == null) throw new IllegalStateException("结算基价保存未返回版本编号");
    return jdbc.queryForObject("SELECT * FROM lp_settlement_base_price WHERE id=? AND business_unit_type=?", ROW,
        key.getKey().longValue(), scope.businessUnitType());
  }
}
