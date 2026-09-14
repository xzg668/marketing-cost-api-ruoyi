package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalaryHistoryRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TechnicalDataSalaryReferenceMapper {

  @Select("""
      <script>
      SELECT source_product.id AS source_product_id,
             source_version.id AS source_version_id,
             source_version.version_no AS source_version_no,
             source_product.material_no,
             source_product.product_name,
             source_version.product_model,
             source_product.accounting_month AS valid_from_month,
             source_version.content_fingerprint,
             source_version.salary_total_amount AS total_amount,
             (SELECT COUNT(*) FROM lp_quote_tech_salary_item item
               WHERE item.version_id=source_version.id) AS item_count
        FROM lp_quote_tech_product source_product
        JOIN lp_quote_tech_task source_task ON source_task.id=source_product.task_id
        JOIN lp_quote_tech_data_version source_version
          ON source_version.id=source_product.effective_version_id
       WHERE source_product.id&lt;&gt;#{targetProductId}
         AND source_product.active_flag=1
         AND source_task.active_flag=1
         AND source_task.task_status='APPROVED'
         AND source_version.version_status='APPROVED'
         AND source_task.business_unit_type=#{businessUnitType}
         AND source_task.applicable_org_code=#{applicableOrgCode}
         AND source_product.accounting_month&lt;=#{accountingMonth}
         AND source_version.content_fingerprint IS NOT NULL
         AND EXISTS (SELECT 1 FROM lp_quote_tech_salary_item item
                      WHERE item.version_id=source_version.id)
         AND NOT EXISTS (
             SELECT 1 FROM lp_quote_tech_salary_item invalid_item
              WHERE invalid_item.version_id=source_version.id
                AND (TRIM(invalid_item.process_code)=''
                  OR TRIM(invalid_item.process_name)=''
                  OR invalid_item.labor_type NOT IN ('DIRECT','INDIRECT')
                  OR invalid_item.working_hours&lt;=0
                  OR invalid_item.original_time_unit NOT IN ('小时/件','分钟/件')
                  OR invalid_item.wage_rate&lt;=0
                  OR invalid_item.rate_unit NOT IN ('元/小时','元/分钟')
                  OR invalid_item.person_coefficient&lt;=0
                  OR invalid_item.amount&lt;=0))
      <if test="sourceVersionId != null">
         AND source_version.id=#{sourceVersionId}
      </if>
      <if test="keyword != null and keyword != ''">
         AND (source_product.material_no LIKE CONCAT('%',#{keyword},'%')
           OR source_product.product_name LIKE CONCAT('%',#{keyword},'%')
           OR source_version.product_model LIKE CONCAT('%',#{keyword},'%'))
      </if>
       ORDER BY source_product.accounting_month DESC,source_version.id DESC
       LIMIT 100
      </script>
      """)
  List<TechnicalDataSalaryHistoryRow> selectApprovedSources(
      @Param("targetProductId") Long targetProductId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("accountingMonth") String accountingMonth,
      @Param("keyword") String keyword,
      @Param("sourceVersionId") Long sourceVersionId);
}
