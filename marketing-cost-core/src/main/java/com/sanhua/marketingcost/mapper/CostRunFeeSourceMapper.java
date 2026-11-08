package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.CostRunFeeQuery;
import com.sanhua.marketingcost.entity.*;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Reads candidate facts only; original costing owns matching priority and arithmetic. */
@Mapper
public interface CostRunFeeSourceMapper {
  record Read<Q>(int slot, Q query) {}
  interface Slotted { Integer getReadSlot(); }

  @lombok.Getter @lombok.Setter
  class MaterialRow extends MaterialMasterRaw implements Slotted { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        SELECT #{r.slot} AS read_slot,e.* FROM lp_material_master_raw e
          WHERE e.active_flag=1 AND e.organization_code=#{r.query.organizationCode}
            AND e.material_code=#{r.query.materialCode}
      </foreach>
      ORDER BY read_slot,id
      </script>
      """)
  List<MaterialRow> readMaterial(@Param("reads") List<Read<CostRunFeeQuery.Material>> reads);

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,e.* FROM lp_material_master_raw e
          WHERE e.active_flag=1 AND e.organization_code=#{r.query.organizationCode}
            AND (e.main_category_code LIKE '10%' OR e.main_category_code LIKE '11%')
            AND LEFT(TRIM(e.material_model),8)=LEFT(#{r.query.model},8)
            AND TRIM(e.material_model)=#{r.query.model}
          ORDER BY e.material_code LIMIT 2)
      </foreach>
      ORDER BY read_slot,material_code
      </script>
      """)
  List<MaterialRow> readNetLossModel(@Param("reads") List<Read<CostRunFeeQuery.NetLossModel>> reads);

  @lombok.Getter @lombok.Setter
  class DepartmentRow extends DepartmentFundRate implements Slotted { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,e.* FROM lp_department_fund_rate e
          WHERE e.rate_year=#{r.query.year} AND e.business_division=#{r.query.division} AND e.expense_subject=#{r.query.subject}
          <if test="r.query.businessUnitType != null">AND e.business_unit_type=#{r.query.businessUnitType}</if>
          ORDER BY e.id DESC LIMIT 1)
      </foreach>
      ORDER BY read_slot,id DESC
      </script>
      """)
  List<DepartmentRow> readDepartment(@Param("reads") List<Read<CostRunFeeQuery.Department>> reads);

  @lombok.Getter @lombok.Setter
  class ManufactureRow extends ManufactureRate implements Slotted { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,e.* FROM lp_manufacture_rate e
          WHERE e.rate_year=#{r.query.year} AND e.match_level=#{r.query.matchLevel} AND e.match_key=#{r.query.matchKey}
          <if test="r.query.businessUnitType != null">AND e.business_unit_type=#{r.query.businessUnitType}</if>
          ORDER BY e.id DESC LIMIT 1)
      </foreach>
      ORDER BY read_slot,id DESC
      </script>
      """)
  List<ManufactureRow> readManufacture(@Param("reads") List<Read<CostRunFeeQuery.Manufacture>> reads);

  @lombok.Getter @lombok.Setter
  class ThreeExpenseRow extends ThreeExpenseRate implements Slotted { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,e.* FROM lp_three_expense_rate e
          WHERE e.period_year=#{r.query.year} AND e.product_category=#{r.query.productCategory}
          AND e.product_line=#{r.query.productLine} AND e.business_unit_type=#{r.query.businessUnitType}
          ORDER BY e.id DESC )
      </foreach>
      ORDER BY read_slot,id DESC
      </script>
      """)
  List<ThreeExpenseRow> readThreeExpense(@Param("reads") List<Read<CostRunFeeQuery.ThreeExpense>> reads);

  @lombok.Getter @lombok.Setter
  class OtherExpenseRow extends OtherExpenseRate implements Slotted { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,e.* FROM lp_other_expense_rate e
          WHERE e.material_code=#{r.query.productCode}
          ORDER BY e.id ASC )
      </foreach>
      ORDER BY read_slot,id ASC
      </script>
      """)
  List<OtherExpenseRow> readOtherExpense(@Param("reads") List<Read<CostRunFeeQuery.OtherExpense>> reads);

  @lombok.Getter @lombok.Setter
  class PropertyRow extends ProductProperty implements Slotted { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,e.* FROM lp_product_property e
          WHERE e.product_code=#{r.query.productCode}
          <if test="r.query.year != null">AND e.property_year=#{r.query.year}</if>
          <if test="r.query.businessUnitType != null">AND e.business_unit_type=#{r.query.businessUnitType}</if>
          ORDER BY e.id DESC LIMIT 1)
      </foreach>
      ORDER BY read_slot,id DESC
      </script>
      """)
  List<PropertyRow> readProperty(@Param("reads") List<Read<CostRunFeeQuery.Property>> reads);

  @lombok.Getter @lombok.Setter
  class PropertyRuleRow extends ProductPropertyRule implements Slotted { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,e.* FROM lp_product_property_rule e
          WHERE e.property_year=#{r.query.year} AND e.product_attr=#{r.query.productAttr} AND e.business_unit_type=#{r.query.businessUnitType}
          ORDER BY e.id ASC )
      </foreach>
      ORDER BY read_slot,id ASC
      </script>
      """)
  List<PropertyRuleRow> readPropertyRule(@Param("reads") List<Read<CostRunFeeQuery.PropertyRule>> reads);

  @lombok.Getter @lombok.Setter
  class QualityLossRow extends QualityLossRate implements Slotted { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,e.* FROM lp_quality_loss_rate e
          WHERE e.rate_year=#{r.query.year} AND e.bare_product_code=#{r.query.bareProductCode} AND e.business_unit_type=#{r.query.businessUnitType}
          ORDER BY e.id ASC )
      </foreach>
      ORDER BY read_slot,id ASC
      </script>
      """)
  List<QualityLossRow> readQualityLoss(@Param("reads") List<Read<CostRunFeeQuery.QualityLoss>> reads);

  @lombok.Getter @lombok.Setter
  class DimensionRow extends ThreeExpenseDimensionMapping implements Slotted { private Integer readSlot; }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,e.* FROM lp_three_expense_dimension_mapping e
          WHERE e.dimension_type=#{r.query.dimensionType} AND e.source_system=#{r.query.sourceSystem}
          AND e.source_value=#{r.query.sourceValue} AND e.business_unit_type=#{r.query.businessUnitType} AND e.enabled=1
          ORDER BY e.priority ASC,e.id DESC LIMIT 1)
      </foreach>
      ORDER BY read_slot,id DESC
      </script>
      """)
  List<DimensionRow> readDimension(@Param("reads") List<Read<CostRunFeeQuery.Dimension>> reads);

  @lombok.Getter @lombok.Setter
  class RuleRow implements Slotted {
    private Integer readSlot;
    private Long id;
    private BigDecimal decimalValue;
  }

  @Select("""
      <script>
      <foreach collection="reads" item="r" separator=" UNION ALL ">
        (SELECT #{r.slot} AS read_slot,id,decimal_value FROM lp_cost_business_rule
          WHERE rule_code=#{r.query.ruleCode} AND enabled=1
            AND business_unit_type IN ('*',COALESCE(NULLIF(#{r.query.businessUnitType},''),'*'))
            AND effective_from &lt;= #{r.query.pricingMonth}
            AND (effective_to IS NULL OR effective_to &gt;= #{r.query.pricingMonth})
          ORDER BY CASE WHEN business_unit_type=#{r.query.businessUnitType} THEN 0 ELSE 1 END,
            effective_from DESC,id DESC LIMIT 1)
      </foreach>
      ORDER BY read_slot
      </script>
      """)
  List<RuleRow> readRule(@Param("reads") List<Read<CostRunFeeQuery.Rule>> reads);
}
