package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable display projection; the costing check and assigned/submitted work stay intact. */
@Repository
@lombok.extern.slf4j.Slf4j
public class TechnicalDataPendingState {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  /** Retained workspaces must not revive deleted products or an obsolete quotation identity. */
  private static final String ACTIVE_PRODUCT="""
      EXISTS (SELECT 1 FROM oa_form_item pi JOIN oa_form pf ON pf.id=pi.oa_form_id
        WHERE pi.id=w.oa_form_item_id AND pi.deleted=0 AND pf.deleted=0
          AND BINARY pf.business_unit_type=BINARY w.business_unit_type
          AND BINARY pf.oa_no=BINARY w.oa_no)
      """;
  private static final String ELIGIBLE="""
      w.last_checked_at IS NOT NULL AND w.technical_check_json IS NOT NULL
      AND w.technical_check_fingerprint IS NOT NULL
      AND (JSON_CONTAINS(JSON_EXTRACT(w.technical_check_json,'$.check.modules[*].required'),'true')
        OR JSON_CONTAINS(JSON_EXTRACT(w.technical_check_json,'$.check.modules[*].reasonCode'),'"PRICE_BASIS_UNCONFIRMED"')
        OR JSON_CONTAINS(w.technical_check_json,JSON_OBJECT('moduleType','SALARY','availability','UNCONFIRMED',
          'reasonCode','SALARY_SOURCE_UNCONFIRMED'),'$.check.modules'))
      AND NOT EXISTS (SELECT 1 FROM lp_quote_tech_task t WHERE t.oa_form_item_id=w.oa_form_item_id
        AND BINARY t.accounting_month=BINARY w.period_month AND t.active_flag=1)
      """+" AND "+ACTIVE_PRODUCT;

  public TechnicalDataPendingState(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

  public record Change(Set<String> modules,String unit,Set<String> materials,Set<Long> items) {
    public Change {
      modules=Set.copyOf(modules);materials=Set.copyOf(materials);items=Set.copyOf(items);
      if(modules.isEmpty()) throw new IllegalArgumentException("变更模块不能为空");
    }
  }
  public record Work(long workspaceId,long itemId,String month,String unit,String fingerprint,
      String token,TechnicalDataSourceCheckResponse original) {}

  /** Called by the source-writing transaction, not by the list request. */
  @Transactional
  public int changed(Change change) {
    var args=new ArrayList<Object>();
    args.add(UUID.randomUUID().toString());
    var sql=new StringBuilder("""
        UPDATE lp_quote_costing_workspace w JOIN oa_form_item i ON i.id=w.oa_form_item_id
        SET w.technical_check_json=JSON_SET(w.technical_check_json,
          '$.pending',JSON_SET(COALESCE(JSON_EXTRACT(w.technical_check_json,'$.pending'),JSON_OBJECT()),
            '$.dirty',?,'$.nextAttemptEpoch',0)),w.updated_at=w.updated_at WHERE
        """).append(ELIGIBLE);
    sql.append(" AND (");
    boolean first=true;
    for(String module:change.modules()) {
      if(!first) sql.append(" OR ");first=false;
      sql.append("JSON_CONTAINS(w.technical_check_json,JSON_OBJECT('moduleType',?,'required',CAST('true' AS JSON)),'$.check.modules')");
      args.add(module);
      if("SALARY".equals(module)) {
        sql.append(" OR JSON_CONTAINS(w.technical_check_json,JSON_OBJECT('moduleType','SALARY','availability','UNCONFIRMED','reasonCode','SALARY_SOURCE_UNCONFIRMED'),'$.check.modules')");
      }
      if("PRICE".equals(module)) {
        sql.append(" OR JSON_CONTAINS(w.technical_check_json,JSON_OBJECT('moduleType','PRICE','reasonCode','PRICE_BASIS_UNCONFIRMED'),'$.check.modules')");
      }
    }
    sql.append(")");
    if(change.unit()!=null) {sql.append(" AND w.business_unit_type=?");args.add(change.unit());}
    if(!change.items().isEmpty()) {
      sql.append(" AND w.oa_form_item_id IN (").append(placeholders(change.items().size())).append(")");args.addAll(change.items());
    }
    if(!change.materials().isEmpty()) {
      String values=placeholders(change.materials().size());
      sql.append(" AND (i.material_no IN (").append(values).append(")");args.addAll(change.materials());
      if(change.modules().contains("PRICE") || change.modules().contains("MANUFACTURING")) {
        sql.append(" OR EXISTS (SELECT 1 FROM lp_quote_effective_bom_node n WHERE n.build_batch_id=w.current_bom_build_batch_id AND n.material_code IN (")
            .append(values).append("))");args.addAll(change.materials());
        for(String path:List.of("$.evidence.PRICE.items[*]","$.evidence.PRICE.gaps[*]")) {
          sql.append(" OR EXISTS (SELECT 1 FROM JSON_TABLE(w.technical_check_json,'").append(path)
              .append("' COLUMNS (material_code VARCHAR(128) PATH '")
              .append(path.contains("items")?"$[1]":"$[0]").append("')) e WHERE e.material_code IN (").append(values).append("))");
          args.addAll(change.materials());
        }
      }
      sql.append(")");
    }
    return jdbc.update(sql.toString(),args.toArray());
  }

  /** One-time conversion of old records; new costing writes already contain their projection. */
  @Transactional
  public int initializeLegacy() {
    return jdbc.update("UPDATE lp_quote_costing_workspace w SET technical_check_json=JSON_SET(technical_check_json,'$.pending',JSON_OBJECT('dirty',?,'nextAttemptEpoch',0)),updated_at=updated_at WHERE "
        +ELIGIBLE+" AND JSON_EXTRACT(w.technical_check_json,'$.pending') IS NULL",UUID.randomUUID().toString());
  }

  public List<Work> waiting(int limit) {
    return jdbc.query("""
        SELECT w.id,w.oa_form_item_id,w.period_month,w.business_unit_type,w.technical_check_fingerprint,
          w.technical_check_json,JSON_UNQUOTE(JSON_EXTRACT(w.technical_check_json,'$.pending.dirty')) token
        FROM lp_quote_costing_workspace w WHERE
        """+ACTIVE_PRODUCT+"""
         AND w.last_checked_at IS NOT NULL AND w.technical_check_fingerprint IS NOT NULL
         AND NOT EXISTS (SELECT 1 FROM lp_quote_tech_task t WHERE t.oa_form_item_id=w.oa_form_item_id
           AND BINARY t.accounting_month=BINARY w.period_month AND t.active_flag=1)
         AND JSON_EXTRACT(w.technical_check_json,'$.pending.dirty') IS NOT NULL
         AND COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(w.technical_check_json,'$.pending.nextAttemptEpoch')) AS UNSIGNED),0)<=UNIX_TIMESTAMP()
         ORDER BY w.id LIMIT ?
        """,(row,index)-> {
      try {
        var check=json.treeToValue(json.readTree(row.getString("technical_check_json")).path("check"),TechnicalDataSourceCheckResponse.class);
        if(!java.util.Objects.equals(check.oaFormItemId(),row.getLong("oa_form_item_id"))
            || !java.util.Objects.equals(check.accountingMonth(),row.getString("period_month"))
            || !java.util.Objects.equals(check.fingerprint(),row.getString("technical_check_fingerprint")))
          throw new IllegalStateException("待办检查身份或版本不一致");
        return new Work(row.getLong("id"),row.getLong("oa_form_item_id"),row.getString("period_month"),
            row.getString("business_unit_type"),row.getString("technical_check_fingerprint"),row.getString("token"),TechnicalDataSalarySupplement.apply(check));
      } catch(com.fasterxml.jackson.core.JsonProcessingException | RuntimeException ex) {
        retry(new Work(row.getLong("id"),row.getLong("oa_form_item_id"),row.getString("period_month"),
            row.getString("business_unit_type"),row.getString("technical_check_fingerprint"),row.getString("token"),null));
        log.warn("待办保存记录不完整，保留原状态：workspace={}",row.getLong("id"),ex);
        return null;
      }
    },limit).stream().filter(java.util.Objects::nonNull).toList();
  }

  @Transactional
  public boolean complete(Work work,TechnicalDataSourceCheckResponse check) {
    try {
      return jdbc.update("""
          UPDATE lp_quote_costing_workspace SET technical_check_json=JSON_SET(
            JSON_REMOVE(technical_check_json,'$.pending.dirty','$.pending.nextAttemptEpoch'),
            '$.pending.check',CAST(? AS JSON)),updated_at=updated_at
          WHERE id=? AND technical_check_fingerprint=?
            AND JSON_UNQUOTE(JSON_EXTRACT(technical_check_json,'$.pending.dirty'))=?
          """,json.writeValueAsString(check),work.workspaceId(),work.fingerprint(),work.token())==1;
    } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalStateException(ex);}
  }

  @Transactional
  public void retry(Work work) {
    jdbc.update("""
        UPDATE lp_quote_costing_workspace SET technical_check_json=JSON_SET(technical_check_json,
          '$.pending.nextAttemptEpoch',UNIX_TIMESTAMP()+30),updated_at=updated_at WHERE id=?
          AND technical_check_fingerprint=? AND JSON_UNQUOTE(JSON_EXTRACT(technical_check_json,'$.pending.dirty'))=?
        """,work.workspaceId(),work.fingerprint(),work.token());
  }

  private static String placeholders(int size) {return String.join(",",java.util.Collections.nCopies(size,"?"));}
}
