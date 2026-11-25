package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementPriceRepository {
  public record RuleKey(String materialCode, SettlementPriceType priceType) {}
  public record ImportPage(List<Map<String, Object>> rows, long total) {}
  private static final String IMPORT_GROUP =
      "CASE WHEN import_batch_token IS NOT NULL THEN import_batch_token ELSE CONCAT(price_month,'/',source_file_sha256,'/',HEX(source_file_name)) END";
  private static final String IMPORT_FILTER = """
      business_unit_type=:unit AND source_system='EXCEL'
        AND source_file_sha256 IS NOT NULL AND source_file_name IS NOT NULL
      """;
  private static final BeanPropertyRowMapper<SettlementPriceRecord> ROW = BeanPropertyRowMapper.newInstance(SettlementPriceRecord.class);
  private final NamedParameterJdbcTemplate jdbc;

  public SettlementPriceRepository(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

  public Optional<SettlementPriceRecord> byId(String businessUnit, long id) {
    return jdbc.query("SELECT * FROM lp_settlement_price WHERE business_unit_type=:bu AND id=:id", Map.of("bu", businessUnit, "id", id), ROW)
        .stream().findFirst();
  }

  public List<SettlementPriceRecord> history(SettlementScope scope, String code, SettlementPriceType type) {
    return jdbc.query("SELECT * FROM lp_settlement_price WHERE business_unit_type=:bu AND material_code=:code "
        + "AND price_type=:type AND price_month=:month ORDER BY revision_no DESC",
        Map.of("bu", scope.businessUnitType(), "code", code, "type", type.name(), "month", scope.priceMonth()), ROW);
  }

  public List<SettlementPriceRecord> effective(SettlementScope scope, String code, SettlementPriceType type) {
    return selectHeads(scope, true, code == null || code.isBlank() ? List.of() : List.of(code.trim()), type);
  }

  public Map<RuleKey, SettlementPriceRecord> effectiveBatch(SettlementScope scope, Collection<RuleKey> requested) {
    if (requested.isEmpty()) return Map.of();
    var keys = Set.copyOf(requested);
    Map<RuleKey, SettlementPriceRecord> result = new LinkedHashMap<>();
    for (var row : selectHeads(scope, true, keys.stream().map(RuleKey::materialCode).distinct().toList(), null)) {
      var key = new RuleKey(row.getMaterialCode(), row.getPriceType());
      if (keys.contains(key)) result.put(key, row);
    }
    return result;
  }

  public Map<RuleKey, SettlementPriceRecord> importHeads(SettlementScope scope, Collection<String> codes) {
    if (codes.isEmpty()) return Map.of();
    Map<RuleKey, SettlementPriceRecord> result = new LinkedHashMap<>();
    for (var row : selectHeads(scope, false, codes, null)) result.put(new RuleKey(row.getMaterialCode(), row.getPriceType()), row);
    return result;
  }

  public List<SettlementPriceRecord> generationRules(String unit) {
    return jdbc.query("SELECT ranked.* FROM (SELECT p.*,ROW_NUMBER() OVER (PARTITION BY material_code,price_type,price_month "
        + "ORDER BY revision_no DESC) ranking FROM lp_settlement_price p WHERE business_unit_type=:unit AND active=1) ranked "
        + "WHERE ranking=1 ORDER BY price_month,material_code,price_type", Map.of("unit", unit), ROW);
  }

  private List<SettlementPriceRecord> selectHeads(SettlementScope scope, boolean activeOnly,
      Collection<String> codes, SettlementPriceType type) {
    var parameters = new MapSqlParameterSource("bu", scope.businessUnitType()).addValue("month", scope.priceMonth());
    String filter = "business_unit_type=:bu AND price_month<=:month";
    if (activeOnly) filter += " AND active=1";
    if (!codes.isEmpty()) { filter += " AND material_code IN (:codes)"; parameters.addValue("codes", codes); }
    if (type != null) { filter += " AND price_type=:type"; parameters.addValue("type", type.name()); }
    return jdbc.query("SELECT ranked.* FROM (SELECT p.*,ROW_NUMBER() OVER (PARTITION BY material_code,price_type "
        + "ORDER BY price_month DESC,revision_no DESC) AS ranking FROM lp_settlement_price p WHERE " + filter
        + ") ranked WHERE ranking=1" + (activeOnly ? " AND voided_at IS NULL" : "")
        + " ORDER BY material_code,price_type", parameters, ROW);
  }

  public ImportPage imports(String unit, int page, int size) {
    var params = new MapSqlParameterSource("unit", unit).addValue("size", size).addValue("offset", (page - 1) * size);
    var rows = jdbc.queryForList("""
        SELECT MIN(id) AS id,MIN(source_file_name) AS file_name,MIN(price_month) AS month,
          CASE WHEN COUNT(voided_at)=0 THEN 'PUBLISHED'
            WHEN COUNT(voided_at)=COUNT(*) THEN 'VOIDED' ELSE 'PARTIAL' END AS status,
          MIN(created_at) AS imported_at,IF(COUNT(DISTINCT created_by)=1,MIN(created_by),NULL) AS imported_by,
          MAX(voided_at) AS voided_at,MAX(voided_by) AS voided_by,COUNT(*) AS row_count
        FROM lp_settlement_price WHERE
        """ + IMPORT_FILTER + " GROUP BY " + IMPORT_GROUP + " ORDER BY imported_at DESC,id DESC LIMIT :size OFFSET :offset", params);
    long total = jdbc.queryForObject("SELECT COUNT(DISTINCT " + IMPORT_GROUP + ") FROM lp_settlement_price WHERE "
        + IMPORT_FILTER, params, Long.class);
    return new ImportPage(rows, total);
  }

  public List<SettlementPriceRecord> lockImport(String unit, SettlementPriceRecord original) {
    var params = new MapSqlParameterSource("unit", unit).addValue("token", original.getImportBatchToken())
        .addValue("month", original.getPriceMonth()).addValue("sha", original.getSourceFileSha256())
        .addValue("name", original.getSourceFileName());
    String group = original.getImportBatchToken() != null ? "import_batch_token=:token"
        : "import_batch_token IS NULL AND price_month=:month AND source_file_sha256=:sha AND BINARY source_file_name=BINARY :name";
    return jdbc.query("SELECT * FROM lp_settlement_price WHERE " + IMPORT_FILTER + " AND " + group + " ORDER BY id FOR UPDATE", params, ROW);
  }

  public void voidRules(String unit, List<Long> ids, String actor) {
    if (ids.isEmpty()) return;
    jdbc.update("""
        UPDATE lp_settlement_price SET voided_by=:actor,voided_at=CURRENT_TIMESTAMP(6)
        WHERE business_unit_type=:unit AND id IN (:ids) AND voided_at IS NULL
        """, Map.of("unit", unit, "ids", ids, "actor", actor));
  }

  public SettlementPriceRecord insert(SettlementPriceRecord row) {
    var parameters = new BeanPropertySqlParameterSource(row);
    parameters.registerSqlType("priceType", Types.VARCHAR);
    var key = new GeneratedKeyHolder();
    jdbc.update("""
        INSERT INTO lp_settlement_price(
          business_unit_type,material_code,material_name,spec_model,price_type,price_month,revision_no,active,
          given_price,planned_price,markup_ratio,base_settle_price,budget_cu,budget_zn,reference_cu,reference_zn,
          original_copper_weight_g,original_zinc_weight_g,copper_weight_g,zinc_weight_g,weights_confirmed,
          weight_confirmation_note,weights_confirmed_by,weights_confirmed_at,expression,formula_version,tax_divisor,
          material_unit,unit_basis,source_system,source_file_name,source_file_sha256,source_file_ref,source_sheet,
          source_rows_json,source_formula,remark,content_hash,created_by,import_batch_token)
        VALUES(:businessUnitType,:materialCode,:materialName,:specModel,:priceType,:priceMonth,:revisionNo,:active,
          :givenPrice,:plannedPrice,:markupRatio,:baseSettlePrice,:budgetCu,:budgetZn,:referenceCu,:referenceZn,
          :originalCopperWeightG,:originalZincWeightG,:copperWeightG,:zincWeightG,:weightsConfirmed,
          :weightConfirmationNote,:weightsConfirmedBy,:weightsConfirmedAt,:expression,:formulaVersion,:taxDivisor,
          :materialUnit,:unitBasis,:sourceSystem,:sourceFileName,:sourceFileSha256,:sourceFileRef,:sourceSheet,
          :sourceRowsJson,:sourceFormula,:remark,:contentHash,:createdBy,:importBatchToken)
        """, parameters, key, new String[]{"id"});
    if (key.getKey() == null) throw new IllegalStateException("结算价格保存未返回修订编号");
    return byId(row.getBusinessUnitType(), key.getKey().longValue()).orElseThrow();
  }
}
