package com.sanhua.marketingcost.service.pricing.inquiry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Retains originals and source versions. Current heads are selected separately for each identity.
 */
@Component
public final class InquiryPriceRepository {
  public record Source(
      long id,
      long batchId,
      String sourceMonth,
      String identityKey,
      InquiryWorkbookParser.Rule rule,
      String fileName,
      String importedBy,
      LocalDateTime importedAt) {}

  public record Original(String name, byte[] bytes) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec documents;

  public InquiryPriceRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json =
        json.copy()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    this.documents = new MonthlyPriceDocumentCodec(this.json);
  }

  public List<InquiryWorkbookParser.Factor> catalog(String unit) {
    return jdbc.query(
        "SELECT DISTINCT canonical_factor_key,factor_name,short_name FROM lp_factor_identity WHERE"
            + " business_unit_type=? AND canonical_factor_key IS NOT NULL ORDER BY"
            + " canonical_factor_key,factor_name,short_name",
        (row, n) ->
            new InquiryWorkbookParser.Factor(row.getString(1), row.getString(2), row.getString(3)),
        unit);
  }

  /**
   * Same lock for import, finance publication and monthly refresh, including an empty initial
   * range.
   */
  public void lock(String unit) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("询价更新需要事务");
    jdbc.queryForList(
        "SELECT id FROM lp_inquiry_import_batch FORCE INDEX (idx_inquiry_import_scope) "
            + "WHERE business_unit_type=? ORDER BY id FOR UPDATE",
        unit);
  }

  public Map<String, Object> replay(String unit, String requestId) {
    var found =
        jdbc.queryForList(
            "SELECT id,price_month,file_hash,created_by,row_count FROM lp_inquiry_import_batch"
                + " WHERE business_unit_type=? AND request_id=?",
            unit,
            requestId);
    return found.isEmpty() ? null : found.getFirst();
  }

  public long insertBatch(
      String unit,
      String month,
      String actor,
      String requestId,
      String name,
      byte[] bytes,
      String hash,
      List<InquiryWorkbookParser.Rule> rules) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "INSERT INTO lp_inquiry_import_batch"
                      + " (business_unit_type,price_month,request_id,file_name,file_hash,original_file,row_count,created_by)"
                      + " VALUES(?,?,?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          statement.setString(1, unit);
          statement.setString(2, month);
          statement.setString(3, requestId);
          statement.setString(4, name);
          statement.setString(5, hash);
          statement.setBytes(6, bytes);
          statement.setInt(7, rules.size());
          statement.setString(8, actor);
          return statement;
        },
        key);
    long batch = Objects.requireNonNull(key.getKey()).longValue();
    var arguments =
        rules.stream()
            .map(
                rule ->
                    new Object[] {
                      batch,
                      unit,
                      month,
                      identity(rule),
                      InquiryNames.normalize(rule.materialCode()),
                      InquiryNames.normalize(rule.specification()),
                      encode(rule)
                    })
            .toList();
    jdbc.batchUpdate(
        "INSERT INTO"
            + " lp_inquiry_rule(batch_id,business_unit_type,price_month,identity_key,material_key,specification_key,rule_json)"
            + " VALUES(?,?,?,?,?,?,?)",
        arguments);
    return batch;
  }

  public List<Source> current(String unit, String month, boolean lock) {
    // A locking read sees an import that committed while finance waited, even in an existing RR
    // transaction.
    var rows =
        jdbc.query(
            select()
                + " WHERE r.business_unit_type=? AND r.price_month<=? ORDER BY r.batch_id DESC,r.id"
                + (lock ? " FOR UPDATE" : ""),
            this::source,
            unit,
            month);
    return heads(rows);
  }

  public List<Source> matching(
      String unit, String month, Set<String> materials, Set<String> specifications) {
    if (materials.isEmpty() && specifications.isEmpty()) return List.of();
    var clauses = new ArrayList<String>();
    var args = new ArrayList<Object>(List.of(unit, month));
    if (!materials.isEmpty()) {
      clauses.add("r.material_key IN (" + placeholders(materials.size()) + ")");
      args.addAll(materials);
    }
    if (!specifications.isEmpty()) {
      clauses.add("r.specification_key IN (" + placeholders(specifications.size()) + ")");
      args.addAll(specifications);
    }
    args.add(month);
    return heads(
        jdbc.query(
            select()
                + " WHERE r.business_unit_type=? AND r.price_month<=? AND ("
                + String.join(" OR ", clauses)
                + ") AND NOT EXISTS (SELECT 1 FROM lp_inquiry_rule newer WHERE"
                + " newer.business_unit_type=r.business_unit_type AND"
                + " newer.identity_key=r.identity_key AND newer.batch_id>r.batch_id AND"
                + " newer.price_month<=?) ORDER BY r.batch_id DESC,r.id",
            this::source,
            args.toArray()));
  }

  private static List<Source> heads(List<Source> rows) {
    var batches = new HashMap<String, Long>();
    var result = new ArrayList<Source>();
    for (var row : rows) {
      Long batch = batches.putIfAbsent(row.identityKey(), row.batchId());
      if (batch == null || batch == row.batchId()) result.add(row);
    }
    return List.copyOf(result);
  }

  public Map<Long, Map<String, Object>> effective(String unit, String month, Collection<Long> ids) {
    var result = new HashMap<Long, Map<String, Object>>();
    var all = new ArrayList<>(ids);
    for (int start = 0; start < all.size(); start += 256) {
      var chunk = all.subList(start, Math.min(start + 256, all.size()));
      var args = new ArrayList<Object>(List.of(unit, month));
      args.addAll(chunk);
      var rows =
          jdbc.queryForList(
              "SELECT p.* FROM lp_inquiry_effective_price p WHERE p.business_unit_type=? AND"
                  + " p.price_month=? AND p.rule_id IN ("
                  + placeholders(chunk.size())
                  + ") ORDER BY p.id DESC",
              args.toArray());
      rows.forEach(row -> result.putIfAbsent(((Number) row.get("rule_id")).longValue(), row));
    }
    return result;
  }

  public void saveEffective(
      String unit,
      String month,
      long ruleId,
      long financeBatch,
      String hash,
      String status,
      java.math.BigDecimal price,
      String message,
      Object calculation,
      String trigger,
      String actor) {
    jdbc.update(
        "INSERT INTO"
            + " lp_inquiry_effective_price(business_unit_type,price_month,rule_id,finance_batch_id,input_hash,status,unit_price,message,calculation_json,trigger_kind,created_by)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
        unit,
        month,
        ruleId,
        financeBatch,
        hash,
        status,
        price,
        message,
        encode(calculation),
        trigger,
        actor);
  }

  public List<Map<String, Object>> history(String unit, int page, int size) {
    return jdbc.queryForList(
        "SELECT id,price_month,file_name,row_count,created_by,created_at FROM"
            + " lp_inquiry_import_batch WHERE business_unit_type=? ORDER BY id DESC LIMIT ? OFFSET"
            + " ?",
        unit,
        size,
        (page - 1) * size);
  }

  public long historyCount(String unit) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM lp_inquiry_import_batch WHERE business_unit_type=?",
            Long.class,
            unit));
  }

  public List<Source> batch(String unit, long id) {
    original(unit, id);
    return jdbc.query(
        select() + " WHERE r.business_unit_type=? AND r.batch_id=? ORDER BY r.id",
        this::source,
        unit,
        id);
  }

  public Original original(String unit, long id) {
    var rows =
        jdbc.queryForList(
            "SELECT file_name,original_file FROM lp_inquiry_import_batch WHERE business_unit_type=?"
                + " AND id=?",
            unit,
            id);
    if (rows.isEmpty())
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "询价导入记录不存在");
    return new Original(
        rows.getFirst().get("file_name").toString(), (byte[]) rows.getFirst().get("original_file"));
  }

  public String encode(Object value) {
    return documents.encode(value);
  }

  public Map<String, Object> calculation(Map<String, Object> result) {
    try {
      return json.readValue(
          documents.unwrap(result.get("calculation_json").toString()),
          new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("询价刷新依据损坏", invalid);
    }
  }

  private Source source(ResultSet row, int number) throws SQLException {
    try {
      return new Source(
          row.getLong("id"),
          row.getLong("batch_id"),
          row.getString("price_month"),
          row.getString("identity_key"),
          documents.decode(row.getString("rule_json"), InquiryWorkbookParser.Rule.class),
          row.getString("file_name"),
          row.getString("created_by"),
          row.getTimestamp("created_at").toLocalDateTime());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("询价来源记录损坏", invalid);
    }
  }

  private static String select() {
    return "SELECT r.*,b.file_name,b.created_by,b.created_at FROM lp_inquiry_rule r JOIN"
               + " lp_inquiry_import_batch b ON b.id=r.batch_id";
  }

  private static String identity(InquiryWorkbookParser.Rule rule) {
    String item =
        rule.materialCode().isBlank()
            ? "SPEC:" + InquiryNames.normalize(rule.specification())
            : "CODE:" + InquiryNames.normalize(rule.materialCode());
    return MonthlyPriceDocumentCodec.sha256(
        item + "\u0000" + InquiryNames.normalize(rule.supplierName()));
  }

  private static String placeholders(int size) {
    return String.join(",", Collections.nCopies(size, "?"));
  }
}
