package com.sanhua.marketingcost.service.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 把一份已审核区间价草稿作为完整版本原子发布；任何区间行失败都会整体回滚。 */
@Component
public class RangePriceDraftFormalPublisher {
  static final BigDecimal OPEN_UPPER_BOUND = new BigDecimal("999999999999.999999");

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public RangePriceDraftFormalPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Publication publish(
      QuotePriceDraft draft, List<QuotePriceDraftField> fields, String publishBatchNo) {
    if (draft == null || !"RANGE".equals(draft.getPriceType())) {
      throw new IllegalArgumentException("仅支持发布区间价草稿");
    }
    if (!"APPROVED".equals(draft.getDraftStatus())
        || !"PASSED".equals(draft.getValidationStatus())) {
      throw new IllegalStateException("区间价必须先校验并经财务审核通过");
    }
    if (!StringUtils.hasText(publishBatchNo)) throw new IllegalArgumentException("发布批次不能为空");
    String basis = text(find(fields, "COMMON", "RANGE_BASIS"));
    if (!StringUtils.hasText(basis)) basis = "QTY";
    basis = basis.toUpperCase(Locale.ROOT);
    String factorCode = text(find(fields, "COMMON", "FACTOR_CODE"));
    if (StringUtils.hasText(factorCode)) factorCode = factorCode.toUpperCase(Locale.ROOT);
    List<Row> rows = rows(fields);
    if (rows.isEmpty()) throw new IllegalArgumentException("区间价至少包含一段区间");

    Long factorRuleId = null;
    LocalDateTime now = LocalDateTime.now();
    if ("FACTOR".equals(basis)) {
      jdbc.update("""
          UPDATE lp_price_range_factor_rule SET current_flag=0, effective_to=?, updated_at=?
          WHERE business_unit_type=? AND material_code=? AND current_flag=1
          """, draft.getEffectiveFrom(), now, draft.getBusinessUnitType(), draft.getMaterialCode());
      Integer nextVersion = jdbc.queryForObject("""
          SELECT COALESCE(MAX(version_no),0)+1 FROM lp_price_range_factor_rule
          WHERE business_unit_type=? AND material_code=?
          """, Integer.class, draft.getBusinessUnitType(), draft.getMaterialCode());
      jdbc.update("""
          INSERT INTO lp_price_range_factor_rule
            (business_unit_type, material_code, material_name, spec_model, factor_code,
             version_no, import_batch_no, effective_from, effective_to, current_flag,
             created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
          """, draft.getBusinessUnitType(), draft.getMaterialCode(), draft.getMaterialName(),
          firstText(draft.getMaterialModel(), draft.getMaterialSpec()), factorCode,
          nextVersion, publishBatchNo, draft.getEffectiveFrom(), draft.getEffectiveTo(), now, now);
      factorRuleId = jdbc.queryForObject(
          "SELECT id FROM lp_price_range_factor_rule WHERE import_batch_no=? AND material_code=?",
          Long.class, publishBatchNo, draft.getMaterialCode());
    }

    jdbc.update("""
        UPDATE lp_price_range_item SET current_flag=0, effective_to=?, updated_at=?
        WHERE business_unit_type=? AND org_code=? AND material_code=? AND current_flag=1
        """, draft.getEffectiveFrom(), now, draft.getBusinessUnitType(), draft.getOrgCode(),
        draft.getMaterialCode());
    for (Row row : rows) {
      jdbc.update("""
          INSERT INTO lp_price_range_item
            (business_unit_type, org_code, source_name, supplier_code, supplier_name,
             material_code, material_name, spec_model, unit, range_low, range_high,
             range_basis, factor_rule_id, factor_code, import_batch_no, current_flag,
             price_excl_tax, price_incl_tax, tax_included, effective_from, effective_to,
             created_at, updated_at)
          VALUES (?, ?, 'QUOTE_COLLABORATION', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1,
                  ?, ?, ?, ?, ?, ?, ?)
          """, draft.getBusinessUnitType(), draft.getOrgCode(), draft.getSupplierCode(),
          draft.getSupplierName(), draft.getMaterialCode(), draft.getMaterialName(),
          firstText(draft.getMaterialModel(), draft.getMaterialSpec()), draft.getUnit(),
          row.low(), row.high() == null ? OPEN_UPPER_BOUND : row.high(), basis, factorRuleId,
          factorCode, publishBatchNo, row.excl(), row.incl(), draft.getTaxIncluded(),
          draft.getEffectiveFrom(), draft.getEffectiveTo(), now, now);
    }
    List<Long> ids = jdbc.queryForList("""
        SELECT id FROM lp_price_range_item WHERE import_batch_no=? AND material_code=? ORDER BY range_low,id
        """, Long.class, publishBatchNo, draft.getMaterialCode());
    if (ids.size() != rows.size()) throw new IllegalStateException("区间价正式记录落点数量不一致");
    return new Publication("lp_price_range_item", ids.get(0), ids, factorRuleId, publishBatchNo);
  }

  private List<Row> rows(List<QuotePriceDraftField> fields) {
    Map<String, Map<String, QuotePriceDraftField>> grouped = new LinkedHashMap<>();
    for (QuotePriceDraftField field : fields == null ? List.<QuotePriceDraftField>of() : fields) {
      if (!"RANGE_ROW".equals(field.getSectionCode())) continue;
      grouped.computeIfAbsent(field.getRowKey(), ignored -> new LinkedHashMap<>())
          .put(field.getFieldCode(), field);
    }
    List<Row> result = new ArrayList<>();
    grouped.forEach((key, values) -> result.add(new Row(
        decimal(values.get("RANGE_LOW")), decimal(values.get("RANGE_HIGH")),
        decimal(values.get("PRICE_EXCL_TAX")), decimal(values.get("PRICE_INCL_TAX")))));
    result.sort(Comparator.comparing(Row::low));
    return result;
  }

  private BigDecimal decimal(QuotePriceDraftField field) {
    String value = text(field);
    return StringUtils.hasText(value) ? new BigDecimal(value) : null;
  }

  private String text(QuotePriceDraftField field) {
    if (field == null || !StringUtils.hasText(field.getTargetValueJson())) return null;
    try {
      JsonNode node = objectMapper.readTree(field.getTargetValueJson());
      return node.isNull() ? null : node.asText().trim();
    } catch (Exception exception) {
      throw new IllegalArgumentException("区间价草稿字段格式错误：" + field.getFieldCode(), exception);
    }
  }

  private static QuotePriceDraftField find(
      List<QuotePriceDraftField> fields, String section, String code) {
    return fields.stream().filter(field -> section.equals(field.getSectionCode())
        && code.equals(field.getFieldCode())).findFirst().orElse(null);
  }

  private static String firstText(String first, String second) {
    return StringUtils.hasText(first) ? first : second;
  }

  private record Row(BigDecimal low, BigDecimal high, BigDecimal excl, BigDecimal incl) {}

  public record Publication(
      String sourceTable, Long primarySourceId, List<Long> sourceIds,
      Long factorRuleId, String publishBatchNo) {}
}
