package com.sanhua.marketingcost.service.pricing.invoice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic incremental imports; the unique invoice identity lives in the current table, revisions in immutable batches. */
@Service
public final class InvoicePriceService {
  public record Published(long batchId, int rows, int duplicates, String fileName) {}
  public record Page(List<Map<String,Object>> records, long total) {}
  public record Original(String name, byte[] bytes) {}
  private final JdbcTemplate jdbc;
  private final InvoiceWorkbookParser parser;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  public InvoicePriceService(JdbcTemplate jdbc, InvoiceWorkbookParser parser, ObjectMapper json, PlatformTransactionManager manager) {
    this.jdbc = jdbc; this.parser = parser; this.json = json;
    transaction = new TransactionTemplate(manager); transaction.setIsolationLevelName("ISOLATION_REPEATABLE_READ"); transaction.setTimeout(30);
  }
  public InvoiceWorkbookParser.Parsed preview(byte[] bytes) { return parser.parse(bytes); }

  public Published publish(String unit, String org, String actor, String requestId, String fileName, byte[] bytes) {
    scope(unit,org);
    if (actor == null || actor.isBlank() || actor.length() > 64) throw new IllegalArgumentException("缺少有效导入人");
    if (requestId == null || !UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) throw new IllegalArgumentException("无效导入请求编号");
    String name = fileName == null ? "发票价格.xlsx" : fileName.replace('\\','/'); name = name.substring(name.lastIndexOf('/') + 1);
    if (name.isBlank() || name.length() > 255) throw new IllegalArgumentException("文件名无效或过长");
    var parsed = parser.parse(bytes); String hash = sha256(bytes), originalName = name;
    for (int attempt = 0; ; attempt++) {
      try { return Objects.requireNonNull(transaction.execute(status -> store(unit,org,actor,requestId,originalName,bytes,hash,parsed))); }
      catch (org.springframework.dao.TransientDataAccessException contention) { if (attempt >= 2) throw contention; }
    }
  }
  private Published store(String unit, String org, String actor, String requestId, String name, byte[] bytes,
      String hash, InvoiceWorkbookParser.Parsed parsed) {
    // Lock the business-unit range before assigning a batch ID, so successful imports have a total order.
    jdbc.queryForList("SELECT id FROM lp_invoice_import_batch FORCE INDEX (idx_invoice_import_scope) WHERE business_unit_type=? ORDER BY org_code,id FOR UPDATE", unit);
    var replay = jdbc.queryForList("SELECT * FROM lp_invoice_import_batch WHERE business_unit_type=? AND request_id=?", unit,requestId);
    if (!replay.isEmpty()) {
      var saved = replay.getFirst();
      if (!org.equals(saved.get("org_code")) || !hash.equals(saved.get("file_hash")) || !actor.equals(saved.get("created_by")))
        throw new IllegalArgumentException("导入请求已用于不同文件、组织或账号");
      return new Published(((Number)saved.get("id")).longValue(),((Number)saved.get("row_count")).intValue(),
          ((Number)saved.get("duplicate_count")).intValue(),saved.get("file_name").toString());
    }
    String rowsJson;
    try { rowsJson = json.writeValueAsString(parsed.rows()); }
    catch (java.io.IOException invalid) { throw new IllegalStateException("无法保存发票导入依据",invalid); }
    var keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      var statement = connection.prepareStatement("INSERT INTO lp_invoice_import_batch(business_unit_type,org_code,request_id,file_name,file_hash,original_file,rows_json,row_count,duplicate_count,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
      statement.setString(1,unit); statement.setString(2,org); statement.setString(3,requestId); statement.setString(4,name);
      statement.setString(5,hash); statement.setBytes(6,bytes); statement.setString(7,rowsJson);
      statement.setInt(8,parsed.rows().size()); statement.setInt(9,parsed.duplicateCount()); statement.setString(10,actor); return statement;
    },keys);
    long batchId = Objects.requireNonNull(keys.getKey()).longValue();
    String insert = """
        INSERT INTO lp_invoice_price(business_unit_type,org_code,material_code,document_no,supplier_code,supplier_name,
          material_name,specification,pricing_unit,unit_price,currency,voucher_no,business_type,document_type,price_type,sheet_name,source_row,batch_id)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE supplier_name=VALUES(supplier_name),
          material_name=VALUES(material_name),specification=VALUES(specification),pricing_unit=VALUES(pricing_unit),
          unit_price=VALUES(unit_price),currency=VALUES(currency),voucher_no=VALUES(voucher_no),business_type=VALUES(business_type),
          document_type=VALUES(document_type),price_type=VALUES(price_type),sheet_name=VALUES(sheet_name),source_row=VALUES(source_row),batch_id=VALUES(batch_id)
        """;
    var arguments = parsed.rows().stream().map(p -> new Object[]{unit,org,p.materialCode(),p.documentNo(),p.supplierCode(),p.supplierName(),
        p.materialName(),p.specification(),p.unit(),p.unitPrice(),p.currency(),p.voucherNo(),p.businessType(),p.documentType(),p.priceType(),p.sheetName(),p.sourceRow(),batchId}).toList();
    jdbc.batchUpdate(insert,arguments);
    return new Published(batchId,parsed.rows().size(),parsed.duplicateCount(),name);
  }

  public Page current(String unit,String org,String material,int page,int size) {
    scope(unit,org); pagination(page,size); material = material(material);
    String where = "p.business_unit_type=? AND p.org_code=?"; var args = new ArrayList<Object>(List.of(unit,org));
    if (material != null) { where += " AND p.material_code=?"; args.add(material); }
    where += " AND p.batch_id=(SELECT MAX(v.batch_id) FROM lp_invoice_price v WHERE v.business_unit_type=p.business_unit_type AND v.org_code=p.org_code AND v.material_code=p.material_code)";
    long total = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM lp_invoice_price p WHERE " + where,Long.class,args.toArray()));
    args.add(size); args.add((page-1)*size);
    var rows = jdbc.queryForList("SELECT p.*,b.created_at imported_at,b.created_by FROM lp_invoice_price p JOIN lp_invoice_import_batch b ON b.id=p.batch_id WHERE " + where + " ORDER BY p.material_code,p.unit_price DESC,p.document_no,p.supplier_code LIMIT ? OFFSET ?",args.toArray());
    rows.forEach(row->row.replaceAll((key,value)->value instanceof java.math.BigDecimal decimal?decimal.toPlainString():value));
    return new Page(rows,total);
  }
  public Page history(String unit,String org,String material,int page,int size) {
    scope(unit,org); pagination(page,size); material = material(material);
    String where = "business_unit_type=? AND org_code=?"; var args = new ArrayList<Object>(List.of(unit,org));
    if (material != null) { where += " AND JSON_CONTAINS(rows_json,JSON_OBJECT('materialCode',?))"; args.add(material); }
    long total = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM lp_invoice_import_batch WHERE " + where,Long.class,args.toArray()));
    args.add(size); args.add((page-1)*size);
    return new Page(jdbc.queryForList("SELECT id,file_name,row_count,duplicate_count,created_at,created_by FROM lp_invoice_import_batch WHERE " + where + " ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray()),total);
  }
  public List<InvoiceWorkbookParser.Price> batchRows(String unit,long id) {
    var row = batch(unit,id,"rows_json");
    try { return json.readValue(row.get("rows_json").toString(),new TypeReference<>() {}); }
    catch (java.io.IOException invalid) { throw new IllegalStateException("发票历史数据损坏",invalid); }
  }
  public Original original(String unit,long id) {
    var row = batch(unit,id,"file_name,original_file"); return new Original(row.get("file_name").toString(),(byte[])row.get("original_file"));
  }
  private Map<String,Object> batch(String unit,long id,String fields) {
    scope(unit,"210");
    var rows = jdbc.queryForList("SELECT " + fields + " FROM lp_invoice_import_batch WHERE business_unit_type=? AND id=?",unit,id);
    if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"发票导入记录不存在");
    return rows.getFirst();
  }
  private static void scope(String unit,String org) {
    if (!Set.of("COMMERCIAL","HOUSEHOLD").contains(Objects.toString(unit,"")) || !Set.of("210","220").contains(Objects.toString(org,"")))
      throw new IllegalArgumentException("必须指定有效业务单元和取价组织");
  }
  private static void pagination(int page,int size) { if(page<1||page>1_000_000||size<1||size>100)throw new IllegalArgumentException("无效分页参数"); }
  private static String material(String value) { if(value==null||value.isBlank())return null; if(value.length()>64||!value.equals(value.strip()))throw new IllegalArgumentException("请输入精确料号"); return value; }
  private static String sha256(byte[] value) {
    try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
}
