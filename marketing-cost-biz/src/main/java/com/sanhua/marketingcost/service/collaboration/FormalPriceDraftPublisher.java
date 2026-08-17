package com.sanhua.marketingcost.service.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.mapper.QuotePriceDraftMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** QCBP-21 阶段A：复用现有正式价表落点，不给正式表增加协作字段。 */
@Service
public class FormalPriceDraftPublisher {
  /**
   * Existing route table {@code lp_material_price_type.source} is a 32-character origin marker,
   * not the collaboration publish-batch trace.  The full batch number is retained in the new
   * collaboration draft/review tables, so never put {@code batchNo} into this legacy column.
   */
  static final String ROUTE_SOURCE = "quote_collab";
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final QuotePriceDraftRepository repository;
  private final QuotePriceDraftMapper draftMapper;
  private final RangePriceDraftFormalPublisher rangePublisher;

  public FormalPriceDraftPublisher(
      JdbcTemplate jdbc, ObjectMapper objectMapper, QuotePriceDraftRepository repository,
      QuotePriceDraftMapper draftMapper, RangePriceDraftFormalPublisher rangePublisher) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.repository = repository;
    this.draftMapper = draftMapper;
    this.rangePublisher = rangePublisher;
  }

  @Transactional
  public Published publish(
      QuotePriceDraft draft, CollaborationScope scope, String batchNo,
      CollaborationPrincipal finance) {
    if ("PUBLISHED".equals(draft.getDraftStatus())) {
      return new Published(draft.getPublishedSourceTable(), draft.getPublishedSourceId(),
          draft.getPublishBatchNo());
    }
    if (!"APPROVED".equals(draft.getDraftStatus()) || !"PASSED".equals(draft.getValidationStatus())) {
      throw new IllegalStateException("价格草稿尚未审核通过：" + draft.getMaterialCode());
    }
    List<QuotePriceDraftField> fields = repository.findFields(draft.getId(), scope);
    Target target = switch (draft.getPriceType()) {
      case "RANGE" -> {
        var result = rangePublisher.publish(draft, fields, batchNo + "-D" + draft.getId());
        yield new Target(result.sourceTable(), result.primarySourceId());
      }
      case "LINKED" -> publishLinked(draft, fields, batchNo);
      case "FIXED_PURCHASE", "SETTLE_FIXED" -> publishFixed(draft, fields, batchNo);
      default -> throw new IllegalArgumentException("不支持的正式价格类型：" + draft.getPriceType());
    };
    upsertPriceRoute(draft, batchNo);
    int marked = draftMapper.markPublished(draft.getId(), draft.getDraftVersion(),
        target.table(), target.id(), batchNo, scope.businessUnitType(), scope.applicableOrgCode(),
        finance.userId(), finance.userName());
    if (marked != 1) throw new CollaborationOptimisticLockException(
        "价格草稿发布", draft.getId(), draft.getDraftVersion());
    return new Published(target.table(), target.id(), batchNo);
  }

  private Target publishFixed(
      QuotePriceDraft draft, List<QuotePriceDraftField> fields, String batchNo) {
    String sourceType = "SETTLE_FIXED".equals(draft.getPriceType())
        ? "SETTLE_FIXED" : "PURCHASE_FIXED";
    BigDecimal price = decimal(fields, "COMMON",
        "SETTLE_FIXED".equals(draft.getPriceType()) ? "BASE_SETTLE_PRICE" : "PRICE");
    BigDecimal markup = decimal(fields, "COMMON", "MARKUP_RATIO");
    BigDecimal effectivePrice = price;
    if (price != null && markup != null) effectivePrice = price.multiply(BigDecimal.ONE.add(markup));
    jdbc.update("""
        UPDATE lp_price_fixed_item SET effective_to=?, updated_at=NOW()
        WHERE business_unit_type=? AND org_code=? AND material_code=? AND source_type=?
          AND (effective_to IS NULL OR effective_to>=?)
        """, draft.getEffectiveFrom().minusDays(1), draft.getBusinessUnitType(), draft.getOrgCode(),
        draft.getMaterialCode(), sourceType, draft.getEffectiveFrom());
    jdbc.update("""
        INSERT INTO lp_price_fixed_item
          (org_code,source_name,supplier_name,supplier_code,material_name,material_code,
           business_unit_type,spec_model,unit,fixed_price,tax_included,effective_from,effective_to,
           source_type,pricing_month,base_settle_price,markup_ratio,tax_rate,
           current_tax_excluded_price,current_tax_included_price,source_system,source_batch_no,
           created_at,updated_at)
        VALUES (?,'QUOTE_COLLABORATION',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW())
        """, draft.getOrgCode(), draft.getSupplierName(), draft.getSupplierCode(),
        draft.getMaterialName(), draft.getMaterialCode(), draft.getBusinessUnitType(),
        first(draft.getMaterialModel(), draft.getMaterialSpec()), draft.getUnit(), effectivePrice,
        draft.getTaxIncluded(), draft.getEffectiveFrom(), draft.getEffectiveTo(), sourceType,
        taskMonth(draft), "SETTLE_FIXED".equals(draft.getPriceType()) ? price : null, markup,
        draft.getTaxRate(), effectivePrice,
        effectivePrice == null || draft.getTaxRate() == null ? effectivePrice
            : effectivePrice.multiply(BigDecimal.ONE.add(draft.getTaxRate())),
        "QUOTE_COLLAB", batchNo);
    Long id = jdbc.queryForObject("""
        SELECT id FROM lp_price_fixed_item WHERE source_batch_no=? AND material_code=? ORDER BY id DESC LIMIT 1
        """, Long.class, batchNo, draft.getMaterialCode());
    return new Target("lp_price_fixed_item", id);
  }

  private Target publishLinked(
      QuotePriceDraft draft, List<QuotePriceDraftField> fields, String batchNo) {
    String formula = text(fields, "FORMULA", "FORMULA_EXPR");
    String formulaCn = text(fields, "FORMULA", "FORMULA_EXPR_CN");
    jdbc.update("""
        UPDATE lp_price_linked_item SET effective_to=?, updated_at=NOW()
        WHERE business_unit_type=? AND org_code=? AND material_code=? AND deleted=0
          AND (effective_to IS NULL OR effective_to>=?)
        """, draft.getEffectiveFrom().minusDays(1), draft.getBusinessUnitType(), draft.getOrgCode(),
        draft.getMaterialCode(), draft.getEffectiveFrom());
    jdbc.update("""
        INSERT INTO lp_price_linked_item
          (pricing_month,org_code,source_name,supplier_name,supplier_code,material_name,material_code,
           business_unit_type,spec_model,unit,formula_expr,formula_expr_cn,blank_weight,net_weight,
           process_fee,agent_fee,tax_included,effective_from,effective_to,deleted,created_at,updated_at)
        VALUES (? ,?,'QUOTE_COLLABORATION',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,NOW(),NOW())
        """, taskMonth(draft), draft.getOrgCode(), draft.getSupplierName(), draft.getSupplierCode(),
        draft.getMaterialName(), draft.getMaterialCode(), draft.getBusinessUnitType(),
        first(draft.getMaterialModel(), draft.getMaterialSpec()), draft.getUnit(), formula, formulaCn,
        variable(fields, "blank_weight"), variable(fields, "net_weight"),
        variable(fields, "process_fee"), variable(fields, "agent_fee"),
        draft.getTaxIncluded(), draft.getEffectiveFrom(), draft.getEffectiveTo());
    Long id = jdbc.queryForObject("""
        SELECT id FROM lp_price_linked_item WHERE material_code=? AND business_unit_type=?
        ORDER BY id DESC LIMIT 1
        """, Long.class, draft.getMaterialCode(), draft.getBusinessUnitType());
    publishLinkedBindings(id, draft, fields);
    return new Target("lp_price_linked_item", id);
  }

  /** 行局部行情占位符是联动公式的一部分，复制时必须随新正式联动价一起落地。 */
  private void publishLinkedBindings(
      Long linkedItemId, QuotePriceDraft draft, List<QuotePriceDraftField> fields) {
    Map<String, Map<String, String>> rows = new LinkedHashMap<>();
    for (QuotePriceDraftField field : fields) {
      if (!"BINDING".equals(field.getSectionCode())) continue;
      rows.computeIfAbsent(field.getRowKey(), ignored -> new LinkedHashMap<>())
          .put(field.getFieldCode(), jsonText(field.getTargetValueJson()));
    }
    for (Map<String, String> row : rows.values()) {
      String token = row.get("TOKEN_NAME");
      String factor = row.get("FACTOR_CODE");
      if (!StringUtils.hasText(token) || !StringUtils.hasText(factor)) continue;
      jdbc.update("""
          INSERT INTO lp_price_variable_binding
            (linked_item_id,token_name,factor_code,price_source,bu_scoped,effective_date,
             expiry_date,source,confirmed_by,confirmed_at,remark,created_by,created_at,
             updated_by,updated_at,deleted)
          VALUES (?,?,?,?,?,?,?,'SUPPLY_CONFIRMED',?,NOW(),?, ?,NOW(),?,NOW(),0)
          """, linkedItemId, token, factor, row.get("PRICE_SOURCE"),
          integer(row.get("BU_SCOPED"), 1), draft.getEffectiveFrom(), draft.getEffectiveTo(),
          "财务审核", "报价协作发布", "quote_collab", "quote_collab");
    }
  }

  private void upsertPriceRoute(QuotePriceDraft draft, String batchNo) {
    jdbc.update("""
        DELETE FROM lp_material_price_type
        WHERE material_code=? AND business_unit_type=? AND period=?
          AND source_system='quote_collab'
        """, draft.getMaterialCode(), draft.getBusinessUnitType(), taskMonth(draft));
    jdbc.update("""
        INSERT INTO lp_material_price_type
          (material_code,business_unit_type,material_name,material_spec,material_model,unit,
           material_shape,price_type,period,source,priority,effective_from,effective_to,
           source_system,created_at,updated_at)
        VALUES (?,?,?,?,?,?,'采购件',?,?,?,1,?,?,'quote_collab',NOW(),NOW())
        """, draft.getMaterialCode(), draft.getBusinessUnitType(), draft.getMaterialName(),
        draft.getMaterialSpec(), draft.getMaterialModel(), draft.getUnit(),
        priceTypeLabel(draft.getPriceType()), taskMonth(draft), ROUTE_SOURCE,
        draft.getEffectiveFrom(), draft.getEffectiveTo());
  }

  private BigDecimal variable(List<QuotePriceDraftField> fields, String code) {
    return decimal(fields, "VARIABLE", code);
  }

  private BigDecimal decimal(List<QuotePriceDraftField> fields, String section, String code) {
    String value = text(fields, section, code);
    return StringUtils.hasText(value) ? new BigDecimal(value) : null;
  }

  private String text(List<QuotePriceDraftField> fields, String section, String code) {
    return fields.stream().filter(field -> section.equals(field.getSectionCode()))
        .filter(field -> code.equalsIgnoreCase(field.getFieldCode())).findFirst()
        .map(QuotePriceDraftField::getTargetValueJson).filter(StringUtils::hasText)
        .map(value -> {
          try { return objectMapper.readTree(value).asText(); }
          catch (Exception exception) { throw new IllegalArgumentException("价格草稿字段格式错误：" + code); }
        }).orElse(null);
  }

  private String jsonText(String value) {
    if (!StringUtils.hasText(value)) return null;
    try { return objectMapper.readTree(value).asText(); }
    catch (Exception exception) { throw new IllegalArgumentException("价格草稿字段格式错误", exception); }
  }

  private static int integer(String value, int fallback) {
    try { return StringUtils.hasText(value) ? Integer.parseInt(value) : fallback; }
    catch (NumberFormatException exception) { return fallback; }
  }

  private static String priceTypeLabel(String code) {
    return switch (code) {
      case "LINKED" -> "联动价";
      case "RANGE" -> "区间价";
      case "SETTLE_FIXED" -> "结算价";
      default -> "固定价";
    };
  }

  private static String taskMonth(QuotePriceDraft draft) {
    return draft.getEffectiveFrom().toString().substring(0, 7);
  }

  private static String first(String one, String two) {
    return StringUtils.hasText(one) ? one : two;
  }

  private record Target(String table, Long id) {}
  public record Published(String sourceTable, Long sourceId, String batchNo) {}
}
