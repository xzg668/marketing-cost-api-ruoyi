package com.sanhua.marketingcost.service.pricing;

import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceCatalog.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 普通查询只读已生成清单；更新来源与财务选择分别保存。 */
@Repository
public class MaterialPriceSourceRepository {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper json;
  public MaterialPriceSourceRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbc); this.json = json;
  }

  private static final String FROM = """
      FROM lp_material_price_source_row s
      LEFT JOIN lp_material_price_attribute a ON a.business_unit_type=s.business_unit_type
        AND a.price_month=s.price_month
        AND a.price_org_code=s.price_org_code AND a.material_code=s.material_code AND a.price_source_type=s.price_source_type
      """;
  private static final String ATTRIBUTE = "CASE WHEN s.auto_attribute IS NOT NULL THEN s.auto_attribute ELSE a.material_attribute END";
  private static final String WHERE = """
      WHERE s.business_unit_type=:unit AND s.price_month=:month
        AND (:organization='' OR s.price_org_code=:organization)
        AND (:code='' OR (:exact=1 AND s.material_code=:code) OR (:exact=0 AND LOCATE(:code,s.material_code)>0))
        AND (:sourceType='' OR s.price_source_type=:sourceType)
      """;

  public Page list(Map<String,Object> params) {
    String filter = WHERE + " AND (:attributeFilter='' OR (:attributeFilter='EMPTY' AND (" + ATTRIBUTE + ") IS NULL) OR (" + ATTRIBUTE + ")=:attributeFilter)";
    long total = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) " + FROM + filter,params,Long.class));
    var rows = jdbc.query("SELECT s.*," + ATTRIBUTE + " material_attribute,COALESCE(a.row_version,0) row_version,"
        + "(s.auto_attribute IS NOT NULL) automatic "
        + FROM + filter + " ORDER BY s.material_code,s.price_org_code,s.price_source_type LIMIT :limit OFFSET :offset",params,this::entry);
    return new Page((String)params.get("month"),total,rows,state(params));
  }

  private Entry entry(ResultSet r, int ignored) throws SQLException {
    try {
      return new Entry(r.getLong("id"),r.getString("material_code"),r.getString("material_name"),r.getString("material_model"),
          r.getString("material_spec"),r.getString("price_org_code"),r.getString("organization_name"),r.getString("price_source_type"),
          sourceLabel(r.getString("price_source_type")),json.readValue(r.getString("prices_json"),new TypeReference<List<Price>>() {}),
          r.getString("material_attribute"),r.getBoolean("automatic"),r.getLong("row_version"));
    } catch (java.io.IOException failure) { throw new IllegalStateException("价格来源清单保存内容无法读取",failure); }
  }

  public RefreshState state(Map<String,Object> params) {
    var rows = jdbc.query("SELECT * FROM lp_material_price_source_scope WHERE business_unit_type=:unit AND price_month=:month",params,
        (r,n) -> new RefreshState(r.getLong("generation"),r.getTimestamp("refreshed_at")==null ? null : r.getTimestamp("refreshed_at").toLocalDateTime(),
            r.getString("refreshed_by"),r.getInt("material_count"),r.getInt("source_count")));
    return rows.isEmpty() ? new RefreshState(0,null,null,0,0) : rows.getFirst();
  }

  public long lock(Map<String,Object> params) {
    jdbc.update("INSERT INTO lp_material_price_source_scope(business_unit_type,price_month) VALUES(:unit,:month) ON DUPLICATE KEY UPDATE generation=generation",params);
    return Objects.requireNonNull(jdbc.queryForObject("SELECT generation FROM lp_material_price_source_scope WHERE business_unit_type=:unit AND price_month=:month FOR UPDATE",params,Long.class))+1;
  }

  public void replace(Map<String,Object> scope, List<Candidate> rows, long generation, String actor) {
    var batch = new ArrayList<Map<String,Object>>();
    for (var row : rows) {
      var params = new HashMap<String,Object>(scope);
      params.put("organization",row.key().organization()); params.put("code",row.key().materialCode()); params.put("sourceType",row.key().sourceType());
      params.put("name",row.materialName()); params.put("model",row.materialModel()); params.put("spec",row.materialSpec());
      params.put("orgName",row.organizationName()); params.put("autoAttribute",row.autoAttribute()); params.put("generation",generation);
      try { params.put("prices",json.writeValueAsString(row.prices())); }
      catch (java.io.IOException failure) { throw new IllegalStateException("价格来源清单无法保存",failure); }
      batch.add(params);
    }
    for (int start=0;start<batch.size();start+=200) jdbc.batchUpdate("""
        INSERT INTO lp_material_price_source_row(business_unit_type,price_month,price_org_code,material_code,price_source_type,
          material_name,material_model,material_spec,organization_name,prices_json,auto_attribute,generation)
        VALUES(:unit,:month,:organization,:code,:sourceType,:name,:model,:spec,:orgName,:prices,:autoAttribute,:generation)
        ON DUPLICATE KEY UPDATE material_name=VALUES(material_name),material_model=VALUES(material_model),material_spec=VALUES(material_spec),
          organization_name=VALUES(organization_name),prices_json=VALUES(prices_json),auto_attribute=VALUES(auto_attribute),generation=VALUES(generation)
        """,batch.subList(start,Math.min(start+200,batch.size())).toArray(Map[]::new));
    var params = new HashMap<String,Object>(scope); params.put("generation",generation); params.put("actor",actor);
    params.put("materials",rows.stream().map(r -> r.key().organization()+":"+r.key().materialCode()).distinct().count()); params.put("sources",rows.size());
    jdbc.update("DELETE FROM lp_material_price_source_row WHERE business_unit_type=:unit AND price_month=:month AND generation<>:generation",params);
    jdbc.update("""
        UPDATE lp_material_price_source_scope SET generation=:generation,refreshed_at=CURRENT_TIMESTAMP(6),refreshed_by=:actor,
          material_count=:materials,source_count=:sources WHERE business_unit_type=:unit AND price_month=:month
        """,params);
  }

  public List<String> availableMonths(String unit) {
    return jdbc.queryForList("""
        SELECT DISTINCT price_month FROM (
          SELECT CONVERT(price_month USING utf8mb4) COLLATE utf8mb4_0900_ai_ci price_month FROM lp_material_price_source_scope WHERE business_unit_type=:unit
          UNION ALL SELECT CONVERT(pricing_month USING utf8mb4) COLLATE utf8mb4_0900_ai_ci price_month FROM lp_price_fixed_item WHERE business_unit_type=:unit
          UNION ALL SELECT CONVERT(pricing_month USING utf8mb4) COLLATE utf8mb4_0900_ai_ci price_month FROM lp_price_linked_item WHERE business_unit_type=:unit AND deleted=0 AND rule_status IN ('LEGACY','PUBLISHED')
          UNION ALL SELECT CONVERT(pricing_month USING utf8mb4) COLLATE utf8mb4_0900_ai_ci price_month FROM lp_price_range_factor_rule WHERE business_unit_type=:unit AND rule_status IN ('LEGACY','PUBLISHED')
          UNION ALL SELECT CONVERT(price_month USING utf8mb4) COLLATE utf8mb4_0900_ai_ci price_month FROM lp_scrap_price WHERE business_unit_type=:unit
          UNION ALL SELECT CONVERT(price_month USING utf8mb4) COLLATE utf8mb4_0900_ai_ci price_month FROM lp_settlement_price WHERE business_unit_type=:unit AND active=1
          UNION ALL SELECT CONVERT(price_month USING utf8mb4) COLLATE utf8mb4_0900_ai_ci price_month FROM lp_inquiry_rule WHERE business_unit_type=:unit
          UNION ALL SELECT CONVERT(period_month USING utf8mb4) COLLATE utf8mb4_0900_ai_ci price_month FROM lp_quote_costing_workspace WHERE business_unit_type=:unit AND current_bom_build_batch_id IS NOT NULL
        ) months WHERE price_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$' ORDER BY price_month DESC
        """,Map.of("unit",unit),String.class);
  }

  public int insert(Map<String,Object> params) {
    return jdbc.update("""
        INSERT INTO lp_material_price_attribute(business_unit_type,price_month,price_org_code,material_code,price_source_type,
          material_attribute,row_version,updated_by,updated_at) VALUES(:unit,:month,:organization,:code,:sourceType,:attribute,1,:actor,CURRENT_TIMESTAMP(6))
        """,params);
  }
  public int update(Map<String,Object> params) {
    return jdbc.update("""
        UPDATE lp_material_price_attribute SET material_attribute=:attribute,row_version=row_version+1,updated_by=:actor,updated_at=CURRENT_TIMESTAMP(6)
        WHERE business_unit_type=:unit AND price_month=:month AND price_org_code=:organization AND material_code=:code AND price_source_type=:sourceType AND row_version=:version
        """,params);
  }
}
