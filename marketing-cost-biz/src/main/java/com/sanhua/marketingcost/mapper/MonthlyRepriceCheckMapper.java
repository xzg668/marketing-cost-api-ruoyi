package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.entity.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Keyset pages and bounded evidence queries; never return an entire month's BOM to the checker. */
@Mapper
public interface MonthlyRepriceCheckMapper {
  @Select("""
      SELECT * FROM lp_cost_run_task WHERE source_no=#{no} AND scene='MONTHLY_REPRICE'
        AND id>#{after} ORDER BY id LIMIT 64
      """)
  List<CostRunTask> tasks(@Param("no") String no,@Param("after") long after);

  @Select("""
      <script>SELECT r.* FROM lp_monthly_reprice_result r
      WHERE r.reprice_no=#{no} AND r.contract_version=1 AND r.calc_object_key IN
      <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach></script>
      """)
  List<MonthlyRepriceResult> results(@Param("no") String no,@Param("keys") List<String> keys);

  record Counts(long tasks,long results,long duplicateVersions) {}
  @Select("""
      SELECT (SELECT COUNT(*) FROM lp_cost_run_task WHERE source_no=#{no} AND scene='MONTHLY_REPRICE') tasks,
        (SELECT COUNT(*) FROM lp_monthly_reprice_result WHERE reprice_no=#{no} AND contract_version=1) results,
        (SELECT COUNT(*)-COUNT(DISTINCT cost_run_version_id) FROM lp_cost_run_task
          WHERE source_no=#{no} AND scene='MONTHLY_REPRICE' AND cost_run_version_id IS NOT NULL) duplicate_versions
      """)
  Counts counts(String no);

  /** Missing customers are known exclusions; broken membership or lost source evidence is corruption. */
  @Select("""
      SELECT COUNT(*) FROM lp_monthly_reprice_batch b WHERE b.id=#{batchId} AND b.scope_version=1 AND (
        b.source_count<>(SELECT COUNT(*) FROM lp_monthly_reprice_source s WHERE s.batch_id=b.id)
        OR b.unresolved_source_count<>(SELECT COUNT(*) FROM lp_monthly_reprice_source s
          WHERE s.batch_id=b.id AND s.object_id IS NULL AND s.issue_code='MISSING_CUSTOMER')
        OR b.total_count<>(SELECT COUNT(*) FROM lp_monthly_reprice_result r WHERE r.reuse_batch_id=b.id)
        OR EXISTS (SELECT 1 FROM lp_monthly_reprice_source s WHERE s.batch_id=b.id
          AND (s.source_snapshot_json IS NULL
            OR (s.object_id IS NULL AND COALESCE(s.issue_code,'')<>'MISSING_CUSTOMER')
            OR (s.object_id IS NOT NULL AND s.issue_code IS NOT NULL)))
        OR EXISTS (SELECT 1 FROM lp_monthly_reprice_result r WHERE r.reuse_batch_id=b.id AND (
          r.merged_source_count<>(SELECT COUNT(*) FROM lp_monthly_reprice_source s WHERE s.object_id=r.id)
          OR NOT EXISTS (SELECT 1 FROM lp_monthly_reprice_source s WHERE s.object_id=r.id
            AND s.source_cost_version_id=r.source_cost_version_id
            AND BINARY s.oa_no=BINARY r.oa_no AND s.oa_form_item_id=r.oa_form_item_id))))
      """)
  long inconsistentSources(long batchId);

  record Input(long version,long snapshot,long item,String product,String priceOrg) {}
  record Evidence(long version,long bom,long parts) {}
  @Select("""
      <script>
      <foreach collection="inputs" item="i" separator=" UNION ALL ">
        SELECT #{i.version} version,
          (SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot
            WHERE id=#{i.snapshot} AND cost_period_month=#{month} AND product_code=#{i.product}
              AND price_org_code=#{i.priceOrg} AND sync_status='SUCCESS') bom,
          (SELECT COUNT(*) FROM lp_cost_run_part_item p JOIN lp_bom_costing_row r ON r.id=p.bom_row_id
            WHERE p.cost_run_version_id=#{i.version} AND BINARY r.calculation_scope=BINARY #{no}
              AND BINARY r.period_month=BINARY #{month} AND r.oa_form_item_id=#{i.item}
              AND EXISTS(SELECT 1 FROM lp_quote_effective_bom_node e
                WHERE BINARY e.build_batch_id=BINARY r.build_batch_id AND e.origin_monthly_snapshot_id=#{i.snapshot})) parts
      </foreach></script>
      """)
  List<Evidence> evidence(@Param("no") String no,@Param("month") String month,@Param("inputs") List<Input> inputs);
}
