package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.monthly.MonthlyMaterialPriceRow;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Single-table storage; range locks also serialize first use when no price exists yet. */
@Mapper
public interface MonthlyPriceStorageMapper {
  @Select("SELECT * FROM lp_material_monthly_price FORCE INDEX (uk_monthly_price_identity) WHERE family_hash=#{hash} ORDER BY applicability_hash FOR UPDATE")
  List<MonthlyMaterialPriceRow> lockFamily(String hash);

  @Insert("""
      INSERT INTO lp_material_monthly_price(family_hash,pricing_month,business_unit_type,material_code,
        specification,pricing_unit,currency,tax_basis,requested_supplier_code,
        applicability_hash,applicability_json,organization_mask,pricing_type,unit_price,household_rate,
        source_type,source_record_id,supplier_code,supplier_name,source_evidence_hash,source_evidence_json,
        household_source_id,first_use_json,created_by)
      VALUES(#{familyHash},#{pricingMonth},#{businessUnitType},#{materialCode},#{specification},#{pricingUnit},
        #{currency},#{taxBasis},#{requestedSupplierCode},#{applicabilityHash},#{applicabilityJson},
        #{organizationMask},#{pricingType},#{unitPrice},#{householdRate},#{sourceType},#{sourceRecordId},
        #{supplierCode},#{supplierName},#{sourceEvidenceHash},#{sourceEvidenceJson},#{householdSourceId},
        #{firstUseJson},#{createdBy})
      """)
  @Options(useGeneratedKeys=true,keyProperty="id")
  int insertPrice(MonthlyMaterialPriceRow row);

  @Select("""
      <script>SELECT * FROM lp_material_monthly_price WHERE business_unit_type=#{businessUnit} AND id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach> ORDER BY id</script>
      """)
  List<MonthlyMaterialPriceRow> pricesByIds(@Param("businessUnit") String businessUnit,@Param("ids") Collection<Long> ids);

  @Select("""
      <script>SELECT * FROM lp_material_monthly_price WHERE business_unit_type=#{unit} AND family_hash IN
      <foreach collection="hashes" item="hash" open="(" separator="," close=")">#{hash}</foreach> ORDER BY id</script>
      """)
  List<MonthlyMaterialPriceRow> pricesByFamilies(@Param("unit") String unit,@Param("hashes") Collection<String> hashes);
}
