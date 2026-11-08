package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Customer/month scope includes unfinished objects; saved costs and original OA ownership stay separate. */
@Mapper
public interface MonthlyCostComparisonMapper {
  record Scope(String unit,String baseline,String comparison,String oa,Long item,String product,
      boolean review,int offset,int size) {}
  record Coverage(String month,long objects) {}
  record SavedSnapshot(long id,String json) {}
  record TaskInput(long versionId,String inputJson) {}
  // Current contract queues share the repricing number and have a unique batch/object key.
  // Legacy task ownership can differ; aggregate its original successful evidence once.
  String RANKED="""
      WITH result_views AS (
        <choose><when test="s.review">SELECT * FROM lp_monthly_reprice_result</when><otherwise>
      """+MonthlyRepricePublishedSql.RESULTS+"""
        </otherwise></choose>
      ), complete_versions AS (
        SELECT v.id,v.oa_no,v.oa_form_item_id,v.product_code,v.business_unit_type,v.pricing_month,
          v.trial_finished_at,v.cost_run_no,v.algorithm_version,v.fee_policy_version,v.part_item_count,v.cost_item_count,
          v.finance_material_cost,v.total_cost,v.cu_material_adjustment,v.final_quote_amount,v.finance_cu_price,
          v.reprice_no
        FROM lp_quote_cost_run_version v
        JOIN oa_form_item i ON i.id=v.oa_form_item_id AND BINARY i.business_unit_type=BINARY v.business_unit_type
        JOIN oa_form f ON f.id=i.oa_form_id AND BINARY f.oa_no=BINARY v.oa_no
          AND BINARY f.business_unit_type=BINARY v.business_unit_type
        WHERE BINARY v.business_unit_type=BINARY #{s.unit}
          AND v.pricing_month IN (#{s.baseline},#{s.comparison})
          AND v.status IN ('SUCCESS','CONFIRMED','HISTORY','VOIDED','STALE')
          AND v.trial_finished_at IS NOT NULL AND v.total_cost IS NOT NULL
          AND v.part_item_count&gt;0 AND v.cost_item_count&gt;0
          AND v.part_item_count=(SELECT COUNT(*) FROM lp_cost_run_part_item p WHERE p.cost_run_version_id=v.id)
          AND v.cost_item_count=(SELECT COUNT(*) FROM lp_cost_run_cost_item c WHERE c.cost_run_version_id=v.id)
      ), legacy_task_ids AS (
        SELECT old.cost_run_version_id version_id,MAX(old.id) task_id
        FROM lp_monthly_reprice_batch historical
        JOIN lp_cost_run_task old ON old.scene='MONTHLY_REPRICE' AND old.source_no=historical.reprice_no
          AND BINARY old.source_no=BINARY historical.reprice_no
        JOIN complete_versions v ON v.id=old.cost_run_version_id
          AND BINARY old.business_unit_type=BINARY v.business_unit_type AND old.pricing_month=v.pricing_month
          AND BINARY old.oa_no=BINARY v.oa_no AND old.oa_form_item_id=v.oa_form_item_id
          AND BINARY old.product_code=BINARY v.product_code AND BINARY old.source_no=BINARY v.reprice_no
        WHERE historical.scope_version IS NULL AND old.status='SUCCESS'
        GROUP BY old.cost_run_version_id
      ), legacy_ranked AS (
        SELECT v.*,ROW_NUMBER() OVER(PARTITION BY BINARY v.oa_no,v.oa_form_item_id,v.pricing_month
          ORDER BY v.trial_finished_at DESC,v.id DESC) oa_rank,
          COALESCE(JSON_UNQUOTE(JSON_EXTRACT(t.request_snapshot_json,'$.customerIdentity.customerSource')),
            JSON_UNQUOTE(JSON_EXTRACT(t.request_snapshot_json,'$.bomInput.context.customer.source'))) saved_customer_source,
          COALESCE(JSON_UNQUOTE(JSON_EXTRACT(t.request_snapshot_json,'$.customerIdentity.customerValue')),
            JSON_UNQUOTE(JSON_EXTRACT(t.request_snapshot_json,'$.bomInput.context.customer.value'))) saved_customer_value
        FROM complete_versions v LEFT JOIN lp_monthly_reprice_batch b ON BINARY b.reprice_no=BINARY v.reprice_no
        LEFT JOIN legacy_task_ids saved_task ON saved_task.version_id=v.id
        LEFT JOIN lp_cost_run_task t ON t.id=saved_task.task_id
        WHERE (v.reprice_no IS NULL OR (b.id IS NOT NULL AND b.scope_version IS NULL))
          AND (#{s.review} OR v.reprice_no IS NULL OR (b.contract_version IS NULL AND b.status='CONFIRMED')
            OR EXISTS(SELECT 1 FROM result_views published WHERE published.cost_run_version_id=v.id AND published.reprice_no=v.reprice_no))
      ), legacy AS (
        SELECT v.*,
          CASE WHEN saved_customer_source IN ('VERIFIED_CUSTOMER_CODE','OA_HEADER_CUSTOMER')
            AND saved_customer_value IS NOT NULL AND TRIM(saved_customer_value) NOT IN ('','null')
            THEN saved_customer_source END customer_source,
          CASE WHEN saved_customer_source IN ('VERIFIED_CUSTOMER_CODE','OA_HEADER_CUSTOMER')
            AND saved_customer_value IS NOT NULL AND TRIM(saved_customer_value) NOT IN ('','null')
            THEN saved_customer_value END customer_value
        FROM legacy_ranked v WHERE oa_rank=1
      ), candidates AS (
        SELECT b.business_unit_type,r.product_code,r.customer_identity_source customer_source,r.customer_identity_value customer_value,
          CAST(NULL AS CHAR(512)) unmatched_key,b.pricing_month,r.oa_no,r.oa_form_item_id,v.id version_id,
          b.reprice_no,r.id result_id,CAST(NULL AS SIGNED) source_id,r.source_cost_version_id,
          r.merged_source_count source_count,
          CASE WHEN NOT #{s.review} THEN CASE WHEN r.calc_status='SUCCESS' AND v.id IS NULL THEN 'DATA_ISSUE' ELSE r.calc_status END
            WHEN t.status='SUCCESS' AND v.id IS NOT NULL THEN 'SUCCESS'
            WHEN t.id IS NULL OR t.status='SUCCESS' THEN 'DATA_ISSUE' ELSE t.status END status,
          CASE WHEN NOT #{s.review} THEN CASE WHEN r.calc_status='SUCCESS' AND v.id IS NULL THEN '已确认成本不完整' ELSE COALESCE(r.calc_message,t.error_message) END
            WHEN t.id IS NULL THEN '客户核算任务缺失'
            WHEN t.status='SUCCESS' AND v.id IS NULL THEN '保存成本不完整或归属不一致' ELSE t.error_message END reason,
          v.trial_finished_at completed_at,2 priority
        FROM lp_monthly_reprice_batch b JOIN result_views r ON r.reuse_batch_id=b.id
          AND r.contract_version=1 AND r.customer_identity_source IS NOT NULL
        LEFT JOIN lp_cost_run_task t ON t.batch_no=b.reprice_no AND t.calc_object_key=r.calc_object_key
          AND BINARY t.batch_no=BINARY b.reprice_no AND BINARY t.source_no=BINARY b.reprice_no AND t.scene='MONTHLY_REPRICE'
          AND BINARY t.business_unit_type=BINARY b.business_unit_type AND t.pricing_month=b.pricing_month
          AND BINARY t.oa_no=BINARY r.oa_no AND t.oa_form_item_id=r.oa_form_item_id AND BINARY t.product_code=BINARY r.product_code
        LEFT JOIN complete_versions v ON v.id=r.cost_run_version_id AND r.calc_status='SUCCESS'
          AND (NOT #{s.review} OR (t.cost_run_version_id=v.id AND t.status='SUCCESS'))
          AND BINARY v.oa_no=BINARY r.oa_no AND v.oa_form_item_id=r.oa_form_item_id
          AND BINARY v.product_code=BINARY r.product_code AND BINARY v.business_unit_type=BINARY b.business_unit_type
          AND v.pricing_month=b.pricing_month AND BINARY v.reprice_no=BINARY b.reprice_no
        WHERE b.contract_version=1 AND b.scope_version=1 AND BINARY b.business_unit_type=BINARY #{s.unit}
          AND b.pricing_month IN (#{s.baseline},#{s.comparison}) AND (#{s.review} OR b.confirmed_at IS NOT NULL)
        UNION ALL
        SELECT b.business_unit_type,v.product_code,NULL,NULL,CONCAT('SOURCE:',b.id,':',s.id),b.pricing_month,s.oa_no,s.oa_form_item_id,
          NULL,b.reprice_no,NULL,s.id,s.source_cost_version_id,1,
          CASE WHEN s.issue_code='MISSING_CUSTOMER' THEN 'WAIT_CUSTOMER' ELSE 'DATA_ISSUE' END,
          '原报价缺少可靠客户身份，无法按客户跨月匹配',NULL,2
        FROM lp_monthly_reprice_batch b JOIN lp_monthly_reprice_source s ON s.batch_id=b.id AND s.object_id IS NULL
        JOIN lp_quote_cost_run_version v ON v.id=s.source_cost_version_id
        WHERE b.contract_version=1 AND b.scope_version=1 AND BINARY b.business_unit_type=BINARY #{s.unit}
          AND b.pricing_month IN (#{s.baseline},#{s.comparison}) AND (#{s.review} OR b.confirmed_at IS NOT NULL)
        UNION ALL
        SELECT v.business_unit_type,v.product_code,v.customer_source,v.customer_value,
          CASE WHEN v.customer_source IS NULL THEN CAST(JSON_ARRAY('LEGACY',v.pricing_month,v.oa_no,v.oa_form_item_id) AS CHAR CHARACTER SET utf8mb4) END,
          v.pricing_month,v.oa_no,v.oa_form_item_id,v.id,v.reprice_no,NULL,NULL,v.id,1,'SUCCESS',
          CASE WHEN v.customer_source IS NULL THEN '旧版本未保存可靠客户身份，无法按客户跨月匹配' END,v.trial_finished_at,1
        FROM legacy v
      ), identities AS (
        SELECT c.*,CAST(JSON_ARRAY(business_unit_type,product_code,customer_source,customer_value,unmatched_key)
          AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_bin identity_key FROM candidates c
      ), ranked AS (
        SELECT i.*,ROW_NUMBER() OVER(PARTITION BY BINARY identity_key,pricing_month
          ORDER BY priority DESC,completed_at DESC,version_id DESC) successful_rank,
          COUNT(*) OVER(PARTITION BY BINARY identity_key,pricing_month) legacy_source_count
        FROM identities i
      )
      <if test="s.oa != null or s.item != null">
      , matched_object_keys AS (
        SELECT CAST(member.identity_key AS BINARY) identity_key FROM identities member WHERE 1=1
          <if test="s.oa != null">AND member.oa_no=#{s.oa} AND BINARY member.oa_no=BINARY #{s.oa}</if>
          <if test="s.item != null">AND member.oa_form_item_id=#{s.item}</if>
        UNION
        SELECT CAST(matched.identity_key AS BINARY) identity_key
        FROM lp_monthly_reprice_source member JOIN lp_monthly_reprice_result owner ON owner.id=member.object_id
        JOIN identities matched ON BINARY matched.business_unit_type=BINARY owner.business_unit_type
          AND BINARY matched.product_code=BINARY owner.product_code
          AND BINARY matched.customer_source=BINARY owner.customer_identity_source
          AND BINARY matched.customer_value=BINARY owner.customer_identity_value
        WHERE BINARY owner.business_unit_type=BINARY #{s.unit}
          AND owner.pricing_month IN (#{s.baseline},#{s.comparison})
          <if test="s.oa != null">AND member.oa_no=#{s.oa} AND BINARY member.oa_no=BINARY #{s.oa}</if>
          <if test="s.item != null">AND member.oa_form_item_id=#{s.item}</if>
      )
      </if>
      , object_keys AS (
        SELECT DISTINCT CAST(r.identity_key AS BINARY) identity_key FROM ranked r
          <if test="s.oa != null or s.item != null">
          JOIN matched_object_keys selected ON BINARY r.identity_key=selected.identity_key
          </if>
        WHERE r.successful_rank=1
          <if test="s.product != null">AND BINARY r.product_code=BINARY #{s.product}</if>
      )
      """;

  @Select("<script>"+RANKED+" SELECT pricing_month month,COUNT(*) objects FROM ranked WHERE successful_rank=1 GROUP BY pricing_month ORDER BY pricing_month</script>")
  List<Coverage> coverage(@Param("s") Scope scope);

  @Select("<script>"+RANKED+" SELECT COUNT(*) FROM object_keys</script>")
  long countObjects(@Param("s") Scope scope);

  String SELECTIONS="""
      SELECT r.business_unit_type,r.product_code,r.customer_source,r.customer_value,r.unmatched_key,r.pricing_month,
        r.oa_no,r.oa_form_item_id,r.version_id,r.reprice_no,r.result_id,r.source_id,r.source_cost_version_id,
        CASE WHEN r.priority=1 THEN r.legacy_source_count ELSE r.source_count END source_count,r.status,r.reason
      FROM ranked r
      """;
  @Select("<script>"+RANKED+"""
      , object_page AS (
        SELECT identity_key FROM object_keys ORDER BY identity_key LIMIT #{s.offset},#{s.size})
      """+SELECTIONS+"""
      JOIN object_page p ON BINARY r.identity_key=p.identity_key
      WHERE r.successful_rank=1 ORDER BY BINARY r.identity_key,r.pricing_month</script>
      """)
  List<com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonScope.Selected> pageSelections(@Param("s") Scope scope);

  @Select("<script>"+RANKED+SELECTIONS+"""
      JOIN object_keys p ON BINARY r.identity_key=p.identity_key
      WHERE r.successful_rank=1 ORDER BY BINARY r.identity_key,r.pricing_month</script>
      """)
  @ResultType(com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonScope.Selected.class)
  @Options(fetchSize=Integer.MIN_VALUE,useCache=false,resultSetType=org.apache.ibatis.mapping.ResultSetType.FORWARD_ONLY)
  void exportSelections(@Param("s") Scope scope,
      org.apache.ibatis.session.ResultHandler<com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonScope.Selected> rows);

  @Select("""
      <script>SELECT id,oa_no,oa_form_item_id,product_code,business_unit_type,pricing_month,trial_finished_at,cost_run_no,
        algorithm_version,fee_policy_version,part_item_count,cost_item_count,finance_material_cost,total_cost,
        cu_material_adjustment,final_quote_amount,finance_cu_price,reprice_no
      FROM lp_quote_cost_run_version WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY pricing_month,id</script>
      """)
  List<QuoteCostRunVersion> versions(@Param("ids") List<Long> ids);

  String HISTORICAL_MEMBERS="""
      FROM identities member JOIN ranked selected ON selected.version_id=#{versionId} AND selected.successful_rank=1
        AND selected.priority=1 AND member.priority=1 AND BINARY member.identity_key=BINARY selected.identity_key
        AND member.pricing_month=selected.pricing_month
      """;
  @Select("<script>"+RANKED+" SELECT COUNT(*) "+HISTORICAL_MEMBERS+"</script>")
  long countHistoricalSources(@Param("s") Scope scope,@Param("versionId") long versionId);
  @Select("<script>"+RANKED+"""
      SELECT member.version_id id,member.oa_no,member.oa_form_item_id,member.source_cost_version_id,
        member.version_id=selected.version_id selected
      """+HISTORICAL_MEMBERS+" ORDER BY BINARY member.oa_no,member.oa_form_item_id LIMIT #{s.offset},#{s.size}</script>")
  List<com.sanhua.marketingcost.dto.MonthlyRepriceSourceDto> historicalSources(@Param("s") Scope scope,@Param("versionId") long versionId);

  @Select("""
      <script>SELECT id,jianji_snapshot_json json FROM lp_quote_cost_run_version WHERE id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
      """)
  List<SavedSnapshot> snapshots(@Param("ids") List<Long> ids);

  @Select("""
      <script>SELECT cost_run_version_id version_id,request_snapshot_json input_json
      FROM lp_cost_run_task WHERE scene='MONTHLY_REPRICE' AND status='SUCCESS' AND cost_run_version_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
      """)
  List<TaskInput> taskInputs(@Param("ids") List<Long> ids);
}
