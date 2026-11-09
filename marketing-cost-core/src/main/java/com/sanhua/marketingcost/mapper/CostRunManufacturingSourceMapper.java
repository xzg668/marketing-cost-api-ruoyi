package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Batched counterparts of the existing CMS salary and auxiliary source queries. */
@Mapper
public interface CostRunManufacturingSourceMapper {
  record Scope(int costYear, String businessUnitType, String pricingMonth, Set<String> materialCodes) {
    public Scope { materialCodes = Collections.unmodifiableSet(new LinkedHashSet<>(materialCodes)); }
  }
  record Read(int slot, Scope scope) {}

  @lombok.Getter @lombok.Setter
  class BatchSalaryRow extends CmsCostSourceEffective { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        SELECT #{r.slot} AS read_slot,e.* FROM cms_cost_source_effective e
        WHERE e.cost_year=#{r.scope.costYear} AND e.business_unit_type=#{r.scope.businessUnitType}
          AND e.source_type IN ('SALARY_DIRECT','SALARY_INDIRECT') AND e.parent_code IN
          <foreach collection="r.scope.materialCodes" item="code" open="(" separator="," close=")">#{code}</foreach>
      </foreach>
      ORDER BY read_slot,parent_code,source_type,id
      </script>
      """)
  List<BatchSalaryRow> selectSalarySourcesBatch(
      @Param("reads") List<Read> reads);

  @lombok.Getter @lombok.Setter
  class BatchAuxRow extends AuxCostItemDto { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        SELECT #{r.slot} AS read_slot,e.parent_code AS materialCode,NULL AS refMaterialCode,
          e.subject_code AS auxSubjectCode,e.subject_name AS auxSubjectName,NULL AS floatRate,
          e.amount_yuan AS unitPrice,e.source_row_ids AS sourceRowIds,e.period AS sourcePeriod,e.source_table AS sourceTable,
          'DIRECT' AS amountCalcMode,NULL AS displayOrder,'CMS_EFFECTIVE' AS source
        FROM cms_cost_source_effective e
        WHERE e.cost_year=#{r.scope.costYear} AND e.period &lt;= #{r.scope.pricingMonth}
          AND e.business_unit_type=#{r.scope.businessUnitType} AND e.source_type='AUX_SUBJECT'
          AND (e.subject_name IS NULL OR TRIM(e.subject_name) &lt;&gt; '包装辅料') AND e.parent_code IN
          <foreach collection="r.scope.materialCodes" item="code" open="(" separator="," close=")">#{code}</foreach>
      </foreach>
      ORDER BY read_slot,auxSubjectCode
      </script>
      """)
  List<BatchAuxRow> selectEffectiveAuxCostItemsBatch(
      @Param("reads") List<Read> reads);

}
