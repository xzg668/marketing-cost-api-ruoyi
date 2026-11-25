package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.service.pricing.monthly.VoidedSourceMonthlyPrices;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkbookPriceImportHistory {
  public enum Kind { SCRAP_PRICE, RANGE_ORIGINAL }
  public record Page(List<Map<String,Object>> rows,long total) {}
  public record Original(String fileName,byte[] bytes) {}

  private static final String SCOPE = """
      business_unit_type=:unit AND import_type=:type AND status IN ('SUCCESS','PUBLISHED','VOIDED')
        AND technical_version_id IS NULL
      """;
  private static final String RANGE_GROUP = """
      CASE WHEN range_workbook_group IS NOT NULL THEN range_workbook_group
        WHEN file_sha256 IS NOT NULL AND content_hash IS NOT NULL AND file_name IS NOT NULL
          THEN CONCAT(price_month,'/',file_sha256,'/',content_hash,'/',HEX(file_name))
        ELSE CONCAT('id:',id) END
      """;
  private final NamedParameterJdbcTemplate jdbc;
  private final FactorIdentityMapper finance;
  private final VoidedSourceMonthlyPrices monthly;
  private final TechnicalDataPendingState pending;
  private final TransactionTemplate transaction;

  public WorkbookPriceImportHistory(NamedParameterJdbcTemplate jdbc,FactorIdentityMapper finance,
      VoidedSourceMonthlyPrices monthly,TechnicalDataPendingState pending,PlatformTransactionManager manager) {
    this.jdbc=jdbc;this.finance=finance;this.monthly=monthly;this.pending=pending;
    transaction=new TransactionTemplate(manager);
    transaction.setIsolationLevelName("ISOLATION_READ_COMMITTED");
  }

  public Page list(String unit,Kind type,int page,int size) {
    if(page<1 || page>1_000_000 || size<1 || size>100)throw new IllegalArgumentException("分页参数无效");
    var args=new HashMap<String,Object>(scope(unit,type));
    args.put("offset",(page-1)*size);
    args.put("size",size);
    String group=group(type);
    String from=" FROM lp_factor_upload_batch WHERE "+SCOPE;
    var rows=jdbc.queryForList("""
        SELECT MIN(id) id,MIN(file_name) file_name,MIN(price_month) month,SUM(NULLIF(linked_row_count,0)) row_count,
          MIN(created_at) imported_at,MIN(uploaded_by) imported_by,
          IF(SUM(status='VOIDED')=COUNT(*),'VOIDED','PUBLISHED') status,
          MAX(voided_at) voided_at,MAX(voided_by) voided_by
        """+from+" GROUP BY "+group+" ORDER BY imported_at DESC,id DESC LIMIT :size OFFSET :offset",args);
    return new Page(rows,jdbc.queryForObject("SELECT COUNT(DISTINCT "+group+")"+from,args,Long.class));
  }

  public Original original(String unit,Kind type,long id) {
    var row=get(unit,type,id,"file_name,source_workbook");
    if(!(row.get("source_workbook") instanceof byte[] bytes) || bytes.length==0)
      throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"这批导入未留存原件");
    return new Original(Objects.toString(row.get("file_name"),"原稿.xlsx"),bytes);
  }

  public void withdrawScrapRow(String unit,long id,long version) {
    var args=new HashMap<String,Object>(scope(unit,Kind.SCRAP_PRICE));
    args.put("id",id);
    transaction.executeWithoutResult(status->{
      finance.lockForFinancePublication(unit);
      var rows=jdbc.queryForList("""
          SELECT p.voided,p.row_version,r.id root_id,r.rule_revision,r.material_code
          FROM lp_scrap_price p JOIN lp_scrap_price r ON r.id=COALESCE(p.source_rule_id,p.id)
            AND r.business_unit_type=p.business_unit_type
            AND (p.source_rule_id IS NULL OR r.rule_revision=p.source_rule_revision)
          WHERE p.business_unit_type=:unit AND p.id=:id FOR UPDATE
          """,args);
      if(rows.isEmpty() || ((Number)rows.getFirst().get("row_version")).longValue()!=version)
        throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"废料价格已变化，请刷新后重试");
      var row=rows.getFirst();
      if(((Number)row.get("voided")).intValue()==1)return;
      long root=((Number)row.get("root_id")).longValue();
      int revision=((Number)row.get("rule_revision")).intValue();
      args.put("root",root);args.put("revision",revision);
      jdbc.update("""
          UPDATE lp_scrap_price SET voided=1,row_version=row_version+1 WHERE business_unit_type=:unit AND voided=0
            AND (id=:root OR (origin_type='CARRY_FORWARD' AND source_rule_id=:root AND source_rule_revision=:revision))
          """,args);
      monthly.scrapRow(unit,root,revision);
      pending.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"),unit,Set.of(row.get("material_code").toString()),Set.of()));
    });
  }

  public void withdrawRangeRow(String unit,long id) {
    var args=new HashMap<String,Object>(scope(unit,Kind.RANGE_ORIGINAL));
    args.put("id",id);
    transaction.executeWithoutResult(status->{
      finance.lockForFinancePublication(unit);
      var rows=jdbc.queryForList("""
          SELECT material_code,row_voided FROM lp_price_range_item
          WHERE business_unit_type=:unit AND id=:id FOR UPDATE
          """,args);
      if(rows.isEmpty()) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"区间价格不存在");
      var row=rows.getFirst();
      if(((Number)row.get("row_voided")).intValue()==1)return;
      jdbc.update("UPDATE lp_price_range_item SET row_voided=1 WHERE business_unit_type=:unit AND id=:id",args);
      monthly.rangeRow(unit,id);
      pending.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"),unit,Set.of(row.get("material_code").toString()),Set.of()));
    });
  }

  public void withdraw(String unit,Kind type,long id,String actor) {
    var scope=scope(unit,type);
    if(actor==null || actor.isBlank() || actor.length()>64)throw new IllegalArgumentException("操作人无效");
    transaction.executeWithoutResult(status->{
      finance.lockForFinancePublication(unit);
      var batch=get(unit,type,id,"id,batch_no,"+group(type)+" workbook_group");
      var args=new HashMap<String,Object>(scope);
      args.put("group",batch.get("workbook_group"));
      var ids=jdbc.queryForList("SELECT id FROM lp_factor_upload_batch WHERE "+SCOPE
          +" AND "+group(type)+"=:group AND status<>'VOIDED' ORDER BY id FOR UPDATE",args,Long.class);
      if(ids.isEmpty())return;
      args.put("ids",ids);
      args.put("actor",actor);
      if(type==Kind.SCRAP_PRICE) {
        String batchNo=batch.get("batch_no").toString();
        args.put("batch",batchNo);
        // Copies carry their explicit source ID and revision. Manual corrections keep their own identity.
        jdbc.update("""
            UPDATE lp_scrap_price copy JOIN lp_scrap_price original ON original.id=copy.source_rule_id
              AND original.rule_revision=copy.source_rule_revision AND original.business_unit_type=copy.business_unit_type
            SET copy.voided=1,copy.row_version=copy.row_version+1
            WHERE original.business_unit_type=:unit AND original.import_batch_no=:batch
              AND original.origin_type='IMPORT' AND copy.origin_type='CARRY_FORWARD' AND copy.voided=0
            """,args);
        jdbc.update("""
            UPDATE lp_scrap_price SET voided=1,row_version=row_version+1
            WHERE business_unit_type=:unit AND import_batch_no=:batch AND origin_type='IMPORT' AND voided=0
            """,args);
        monthly.scrap(unit,batchNo);
      } else {
        jdbc.update("""
            UPDATE lp_price_range_item p JOIN lp_price_range_factor_rule r ON r.id=p.factor_rule_id
            SET p.current_flag=0 WHERE r.business_unit_type=:unit AND r.source_upload_batch_id IN (:ids)
              AND r.rule_status='PUBLISHED' AND p.business_unit_type=:unit
            """,args);
        jdbc.update("""
            UPDATE lp_price_range_factor_rule SET rule_status='VOIDED',current_flag=0
            WHERE business_unit_type=:unit AND source_upload_batch_id IN (:ids) AND rule_status='PUBLISHED'
            """,args);
        monthly.range(unit,ids);
      }
      jdbc.update("""
          UPDATE lp_factor_upload_batch SET status='VOIDED',voided_by=:actor,voided_at=CURRENT_TIMESTAMP(6)
          WHERE business_unit_type=:unit AND import_type=:type AND id IN (:ids)
          """,args);
      pending.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"),unit,Set.of(),Set.of()));
    });
  }

  private Map<String,Object> get(String unit,Kind type,long id,String fields) {
    var args=new HashMap<String,Object>(scope(unit,type));args.put("id",id);
    var rows=jdbc.queryForList("SELECT "+fields+" FROM lp_factor_upload_batch WHERE "+SCOPE+" AND id=:id",args);
    if(rows.isEmpty())throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"导入记录不存在");
    return rows.getFirst();
  }

  private static String group(Kind type) {
    return type==Kind.RANGE_ORIGINAL?RANGE_GROUP:"id";
  }

  private static Map<String,Object> scope(String unit,Kind type) {
    if(!Set.of("COMMERCIAL","HOUSEHOLD").contains(Objects.toString(unit,"")))
      throw new IllegalArgumentException("请先选择业务单元");
    return Map.of("unit",unit,"type",type.name());
  }
}
