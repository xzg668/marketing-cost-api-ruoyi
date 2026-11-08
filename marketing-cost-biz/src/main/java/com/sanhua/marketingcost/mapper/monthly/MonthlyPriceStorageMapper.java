package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.monthly.MonthlyMaterialPriceRow;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Single-table storage; range locks also serialize first use when no price exists yet. */
@Mapper
public interface MonthlyPriceStorageMapper {
  // Higher-priority adopted facts remain stable. Invoice imports invalidate only invoice and later fallbacks.
  String CURRENT = "superseded_by_finance_batch_id IS NULL AND (source_type NOT IN ('INVOICE','ODS_ORDER','INQUIRY','TECH_FIXED') OR invoice_revision=COALESCE((SELECT MAX(i.batch_id) FROM lp_invoice_price i WHERE i.business_unit_type=lp_material_monthly_price.business_unit_type AND i.material_code=lp_material_monthly_price.material_code AND (lp_material_monthly_price.organization_mask &amp; IF(i.org_code='210',1,2))!=0),0)) AND (source_type != 'HOUSEHOLD_INQUIRY' OR (household_revision>0 AND household_source_id=(SELECT h.id FROM lp_household_inquiry_price h JOIN lp_household_inquiry_import_batch hb ON hb.id=h.import_batch_id WHERE h.business_unit_type=lp_material_monthly_price.business_unit_type AND h.match_key=(SELECT original.match_key FROM lp_household_inquiry_price original WHERE original.id=lp_material_monthly_price.household_source_id) AND hb.status='PUBLISHED' AND hb.source_month &lt;= lp_material_monthly_price.pricing_month AND (h.material_code IS NOT NULL OR NOT EXISTS (SELECT 1 FROM lp_household_inquiry_price exact_source JOIN lp_household_inquiry_import_batch exact_batch ON exact_batch.id=exact_source.import_batch_id WHERE exact_source.business_unit_type=lp_material_monthly_price.business_unit_type AND exact_source.material_code=lp_material_monthly_price.material_code AND exact_batch.status='PUBLISHED' AND exact_batch.source_month &lt;= lp_material_monthly_price.pricing_month)) ORDER BY hb.source_month DESC,h.revision_no DESC LIMIT 1)))";
  @Select("SELECT id FROM lp_household_inquiry_price FORCE INDEX (uk_household_current_match) WHERE business_unit_type=#{unit} AND current_match=#{matchKey} FOR SHARE")
  List<Long> lockHouseholdCurrent(@Param("unit") String unit,@Param("matchKey") String matchKey);

  @Select("SELECT h.id FROM lp_household_inquiry_price h JOIN lp_household_inquiry_import_batch b ON b.id=h.import_batch_id WHERE h.business_unit_type=#{unit} AND h.match_key IN (CONCAT('C:',#{material}),#{matchKey}) AND b.status='PUBLISHED' AND b.source_month<=#{month} ORDER BY (h.material_code IS NOT NULL) DESC,b.source_month DESC,h.revision_no DESC LIMIT 1 FOR SHARE")
  Long currentHouseholdSource(@Param("unit") String unit,@Param("material") String material,@Param("month") String month,@Param("matchKey") String matchKey);

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

  /** The same indexed next-key locks, acquired in deterministic hash order for a bounded group. */
  @Select("""
      <script>SELECT * FROM lp_material_monthly_price FORCE INDEX (uk_monthly_price_identity)
      WHERE family_hash IN
      <foreach collection="hashes" item="hash" open="(" separator="," close=")">#{hash}</foreach>
      AND
      """ + CURRENT + " ORDER BY family_hash,applicability_hash FOR UPDATE</script>")
  List<MonthlyMaterialPriceRow> lockFamilies(@Param("hashes") List<String> hashes);

  @Insert("""
      INSERT INTO lp_material_monthly_price(family_hash,pricing_month,business_unit_type,material_code,
        specification,pricing_unit,currency,tax_basis,requested_supplier_code,
        applicability_hash,applicability_json,organization_mask,pricing_type,unit_price,household_rate,
        source_type,source_record_id,supplier_code,supplier_name,source_evidence_hash,source_evidence_json,
        household_source_id,first_use_json,created_by,invoice_revision,finance_revision,household_revision)
      VALUES(#{familyHash},#{pricingMonth},#{businessUnitType},#{materialCode},#{specification},#{pricingUnit},
        #{currency},#{taxBasis},#{requestedSupplierCode},#{applicabilityHash},#{applicabilityJson},
        #{organizationMask},#{pricingType},#{unitPrice},#{householdRate},#{sourceType},#{sourceRecordId},
        #{supplierCode},#{supplierName},#{sourceEvidenceHash},#{sourceEvidenceJson},#{householdSourceId},
        #{firstUseJson},#{createdBy},#{invoiceRevision},#{financeRevision},#{householdRevision})
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
