package com.sanhua.marketingcost.service.costing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit business-unit scope also works in the independent costing worker. */
@Repository
public class ProductPartCostRuleStore {
  public static final String TABLE = "lp_product_part_cost_rule";
  private final JdbcTemplate jdbc;

  public ProductPartCostRuleStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<ProductPartCostRule> forProduct(String unit, String product) {
    return jdbc.query("SELECT * FROM lp_product_part_cost_rule WHERE business_unit_type=? AND product_code=? ORDER BY id",
        ProductPartCostRuleStore::row, unit, product);
  }

  public List<ProductPartCostRule> list(String unit) {
    return jdbc.query("SELECT * FROM lp_product_part_cost_rule WHERE business_unit_type=? ORDER BY id DESC",
        ProductPartCostRuleStore::row, unit);
  }

  public ProductPartCostRule get(String unit, long id) {
    var rows = jdbc.query("SELECT * FROM lp_product_part_cost_rule WHERE business_unit_type=? AND id=?",
        ProductPartCostRuleStore::row, unit, id);
    if (rows.isEmpty()) throw new IllegalArgumentException("成品零件成本配置不存在");
    return rows.getFirst();
  }

  public ProductPartCostRule insert(ProductPartCostRule value) {
    var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
    jdbc.update(connection -> {
      var statement = connection.prepareStatement("""
          INSERT INTO lp_product_part_cost_rule(business_unit_type,product_code,product_name,product_spec,
          product_model,part_code,part_name,part_model,shape,manufacture_rate) VALUES(?,?,?,?,?,?,?,?,?,?)
          """, java.sql.Statement.RETURN_GENERATED_KEYS);
      Object[] values = {value.businessUnitType(), value.productCode(), value.productName(), value.productSpec(),
          value.productModel(), value.partCode(), value.partName(), value.partModel(), value.shape(), value.manufactureRate()};
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      return statement;
    }, key);
    return get(value.businessUnitType(), java.util.Objects.requireNonNull(key.getKey()).longValue());
  }

  public ProductPartCostRule update(ProductPartCostRule value) {
    int changed = jdbc.update("""
        UPDATE lp_product_part_cost_rule SET product_code=?,product_name=?,product_spec=?,product_model=?,
        part_code=?,part_name=?,part_model=?,shape=?,manufacture_rate=?,revision=revision+1
        WHERE id=? AND business_unit_type=? AND revision=?
        """, value.productCode(), value.productName(), value.productSpec(), value.productModel(), value.partCode(),
        value.partName(), value.partModel(), value.shape(), value.manufactureRate(), value.id(), value.businessUnitType(), value.revision());
    if (changed != 1) throw new IllegalArgumentException("配置已被修改或删除，请刷新后重试");
    return get(value.businessUnitType(), value.id());
  }

  public void delete(String unit, long id, long revision) {
    if (jdbc.update("DELETE FROM lp_product_part_cost_rule WHERE business_unit_type=? AND id=? AND revision=?",
        unit, id, revision) != 1) throw new IllegalArgumentException("配置已被修改或删除，请刷新后重试");
  }

  private static ProductPartCostRule row(ResultSet row, int index) throws SQLException {
    return new ProductPartCostRule(row.getLong("id"), row.getLong("revision"), row.getString("business_unit_type"),
        row.getString("product_code"), row.getString("product_name"), row.getString("product_spec"), row.getString("product_model"),
        row.getString("part_code"), row.getString("part_name"), row.getString("part_model"), row.getString("shape"), row.getBigDecimal("manufacture_rate"));
  }
}
