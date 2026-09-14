package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPackageReferenceRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TechnicalDataPackageReferenceMapper {

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
             (SELECT COUNT(*) FROM lp_quote_tech_package_item item
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
         AND EXISTS (SELECT 1 FROM lp_quote_tech_package_item item
                      WHERE item.version_id=source_version.id)
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
  List<TechnicalDataPackageReferenceRow> selectApprovedSources(
      @Param("targetProductId") Long targetProductId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("accountingMonth") String accountingMonth,
      @Param("keyword") String keyword,
      @Param("sourceVersionId") Long sourceVersionId);
}
