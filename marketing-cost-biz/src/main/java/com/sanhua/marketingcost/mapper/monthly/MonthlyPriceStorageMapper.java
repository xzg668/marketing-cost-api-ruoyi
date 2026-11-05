package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.monthly.MonthlyMaterialPriceRow;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Single-table storage; range locks also serialize first use when no price exists yet. */
@Mapper
public interface MonthlyPriceStorageMapper {
  // Higher-priority adopted facts remain stable. Invoice imports invalidate only invoice and later fallbacks.
  String CURRENT = "superseded_by_finance_batch_id IS NULL AND (source_type NOT IN ('INVOICE','ODS_ORDER','INQUIRY','TECH_FIXED') OR invoice_revision=COALESCE((SELECT MAX(i.batch_id) FROM lp_invoice_price i WHERE i.business_unit_type=lp_material_monthly_price.business_unit_type AND i.material_code=lp_material_monthly_price.material_code AND (lp_material_monthly_price.organization_mask &amp; IF(i.org_code='210',1,2))!=0),0))";
  @Select("SELECT id FROM lp_factor_identity WHERE business_unit_type=#{unit} ORDER BY id FOR SHARE")
  List<Long> lockFinancePublication(String unit);

  @Select("""
      SELECT id FROM lp_factor_upload_batch WHERE business_unit_type=#{unit} AND price_org_code=#{org}
        AND price_month<=#{month} AND import_type='FINANCE_BASE' AND status='PUBLISHED'
        AND published_at IS NOT NULL ORDER BY price_month DESC,publication_revision DESC LIMIT 1
      """)
  Long currentFinanceBatch(@Param("unit") String unit,@Param("org") String org,@Param("month") String month);

  @Select("SELECT COALESCE(MAX(superseded_by_finance_batch_id),0) FROM lp_material_monthly_price WHERE family_hash=#{hash}")
  long financeRevision(String hash);
  record InvoiceRevision(String materialCode,String orgCode,long revision) {}
  @Select("<script>SELECT material_code,org_code,MAX(batch_id) revision FROM lp_invoice_price WHERE business_unit_type=#{unit} AND material_code IN <foreach collection='codes' item='code' open='(' separator=',' close=')'>#{code}</foreach> GROUP BY material_code,org_code</script>")
  List<InvoiceRevision> invoiceRevisions(@Param("unit") String unit,@Param("codes") Collection<String> codes);

  @Select("<script>SELECT * FROM lp_material_monthly_price FORCE INDEX (uk_monthly_price_identity) WHERE family_hash=#{hash} AND " + CURRENT + " ORDER BY applicability_hash FOR UPDATE</script>")
  List<MonthlyMaterialPriceRow> lockFamily(String hash);

  @Insert("""
      INSERT INTO lp_material_monthly_price(family_hash,pricing_month,business_unit_type,material_code,
        specification,pricing_unit,currency,tax_basis,requested_supplier_code,
        applicability_hash,applicability_json,organization_mask,pricing_type,unit_price,household_rate,
        source_type,source_record_id,supplier_code,supplier_name,source_evidence_hash,source_evidence_json,
        household_source_id,first_use_json,created_by,invoice_revision,finance_revision)
      VALUES(#{familyHash},#{pricingMonth},#{businessUnitType},#{materialCode},#{specification},#{pricingUnit},
        #{currency},#{taxBasis},#{requestedSupplierCode},#{applicabilityHash},#{applicabilityJson},
        #{organizationMask},#{pricingType},#{unitPrice},#{householdRate},#{sourceType},#{sourceRecordId},
        #{supplierCode},#{supplierName},#{sourceEvidenceHash},#{sourceEvidenceJson},#{householdSourceId},
        #{firstUseJson},#{createdBy},#{invoiceRevision},#{financeRevision})
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
      <foreach collection="hashes" item="hash" open="(" separator="," close=")">#{hash}</foreach> AND
      """ + CURRENT + " ORDER BY id</script>")
  List<MonthlyMaterialPriceRow> pricesByFamilies(@Param("unit") String unit,@Param("hashes") Collection<String> hashes);
}
