package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.Statistics;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonthlyRepriceUncalculatedMapper {
  record Query(long batchId,String customer,String product,String status,int offset,int size) {}
  record Item(String kind,long id,Long resultId,String repriceNo,String pricingMonth,String productCode,
      String customer,String oaNo,Long oaFormItemId,Long sourceVersionId,int sourceCount,String status,
      String reasonCode,String reason,String diagnostics,int retryCount,LocalDateTime lastAttemptAt) {}
  record SourceAttempt(long id,int retryCount,LocalDateTime lastAttemptAt,String reason) {}

  @Select("""
      SELECT * FROM lp_monthly_reprice_batch WHERE contract_version=1 AND business_unit_type=#{unit}
        AND pricing_month=#{month} AND (#{review} OR confirmed_at IS NOT NULL)
      """)
  MonthlyRepriceBatch month(@Param("unit") String unit,@Param("month") String month,@Param("review") boolean review);

  @Select("""
      SELECT COALESCE(b.source_count,b.total_count) sources,b.total_count objects,
        (SELECT COUNT(*) FROM lp_monthly_reprice_compute_group g WHERE g.batch_id=b.id) `groups`,
        (SELECT COALESCE(SUM(g.attempt_count),0) FROM lp_monthly_reprice_compute_group g WHERE g.batch_id=b.id) calculation_attempts,
        b.success_count successful,b.failed_count failed,
        (SELECT COUNT(*) FROM lp_cost_run_task t WHERE t.source_no=b.reprice_no AND t.scene='MONTHLY_REPRICE' AND t.status='RUNNING') running,
        (SELECT COUNT(*) FROM lp_cost_run_task t WHERE t.source_no=b.reprice_no AND t.scene='MONTHLY_REPRICE' AND t.status='PENDING') pending,
        COALESCE(b.unresolved_source_count,0) unresolved_sources
      FROM lp_monthly_reprice_batch b WHERE b.id=#{id} AND b.contract_version=1
      """)
  Statistics statistics(long id);

  String ROWS="""
      WITH uncalculated AS (
        SELECT 'TASK' kind,t.id,r.id result_id,b.reprice_no,b.pricing_month,t.product_code,
          COALESCE(r.customer_identity_value,t.customer_name) customer,t.oa_no,t.oa_form_item_id,r.source_cost_version_id source_version_id,
          COALESCE(r.merged_source_count,1) source_count,t.status,
          CASE WHEN r.id IS NULL THEN 'OBJECT_LINK_MISSING'
            ELSE JSON_UNQUOTE(JSON_EXTRACT(t.result_summary_json,'$.failure.code')) END reason_code,
          CASE WHEN r.id IS NULL THEN '对象资料不完整，请先检查批次' ELSE t.error_message END reason,
          t.result_summary_json diagnostics,t.retry_count,COALESCE(t.finished_at,t.started_at,
            STR_TO_DATE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(t.result_summary_json,'$.lastAttemptAt')),'null'),'%Y-%m-%d %H:%i:%s.%f')) last_attempt_at
        FROM lp_monthly_reprice_batch b JOIN lp_cost_run_task t ON t.source_no=b.reprice_no AND t.scene='MONTHLY_REPRICE'
        LEFT JOIN lp_monthly_reprice_result r ON r.reprice_no=b.reprice_no AND r.calc_object_key=t.calc_object_key AND r.contract_version=1
        WHERE b.id=#{q.batchId} AND b.contract_version=1 AND t.status&lt;&gt;'SUCCESS'
        UNION ALL
        SELECT 'SOURCE',s.id,NULL,b.reprice_no,b.pricing_month,v.product_code,NULL,s.oa_no,s.oa_form_item_id,
          s.source_cost_version_id,1,
          CASE WHEN s.issue_code='MISSING_CUSTOMER' AND s.source_snapshot_json IS NOT NULL THEN 'WAIT_CUSTOMER' ELSE 'DATA_ISSUE' END,
          CASE WHEN s.source_snapshot_json IS NULL THEN 'SOURCE_EVIDENCE_MISSING' ELSE s.issue_code END,
          CASE WHEN s.source_snapshot_json IS NULL THEN '原报价保存依据缺失，请先检查批次' ELSE '请完善原报价的客户资料后再次核算' END,
          NULL,0,NULL
        FROM lp_monthly_reprice_batch b JOIN lp_monthly_reprice_source s ON s.batch_id=b.id AND s.object_id IS NULL
        JOIN lp_quote_cost_run_version v ON v.id=s.source_cost_version_id
        WHERE b.id=#{q.batchId} AND b.contract_version=1
      )
      """;
  String FILTER="""
      WHERE (#{q.customer} IS NULL OR LOCATE(#{q.customer},customer)&gt;0)
        AND (#{q.product} IS NULL OR LOCATE(#{q.product},product_code)&gt;0)
        AND (#{q.status} IS NULL OR status=#{q.status})
      """;
  @Select("<script>"+ROWS+" SELECT COUNT(*) FROM uncalculated "+FILTER+"</script>")
  long count(@Param("q") Query query);
  @Select("<script>"+ROWS+" SELECT * FROM uncalculated "+FILTER+" ORDER BY kind,id LIMIT #{q.offset},#{q.size}</script>")
  List<Item> page(@Param("q") Query query);

  @Select("""
      <script>SELECT CAST(a.target_key AS UNSIGNED) id,a.retry_count,a.last_attempt_at,last.change_summary reason
      FROM (SELECT target_key,COUNT(*) retry_count,MAX(operation_time) last_attempt_at,MAX(id) last_id
      FROM lp_monthly_reprice_audit_log WHERE target_type='REPRICE_SOURCE' AND operation_type='RETRY_SOURCE'
        AND target_key IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      GROUP BY target_key) a JOIN lp_monthly_reprice_audit_log last ON last.id=a.last_id</script>
      """)
  List<SourceAttempt> sourceAttempts(@Param("ids") List<String> ids);
}
