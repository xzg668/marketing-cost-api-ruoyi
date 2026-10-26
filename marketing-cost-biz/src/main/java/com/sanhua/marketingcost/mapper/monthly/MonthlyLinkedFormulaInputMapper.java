package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Bounded captures for the existing variable-formula evaluator; never generates current prices. */
@Mapper
public interface MonthlyLinkedFormulaInputMapper {
  @Select("SELECT * FROM lp_price_variable WHERE status='active' AND (business_unit_type=#{unit} OR business_unit_type IS NULL) ORDER BY id")
  List<PriceVariable> variables(@Param("unit") String unit);

  @Select("SELECT * FROM lp_row_local_placeholder WHERE status='active' AND deleted=0 ORDER BY sort_order,id")
  List<RowLocalPlaceholder> placeholders();

  @Select("""
    <script>SELECT b.* FROM lp_price_variable_binding b JOIN lp_price_linked_item r ON r.id=b.linked_item_id
      WHERE r.business_unit_type=#{unit} AND b.expiry_date IS NULL AND b.deleted=0 AND b.linked_item_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach> ORDER BY b.linked_item_id,b.id</script>
    """)
  List<PriceVariableBinding> bindings(@Param("unit") String unit,@Param("ids") Collection<Long> ids);

  @Select("""
    <script>SELECT * FROM lp_factor_identity WHERE business_unit_type=#{unit} AND id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach> ORDER BY id</script>
    """)
  List<FactorIdentity> identities(@Param("unit") String unit,@Param("ids") Collection<Long> ids);

  @Select("""
    <script>SELECT requested.factor_key FROM (
      <foreach collection="keys" item="key" separator=" UNION ALL ">
        SELECT CONVERT(#{key} USING utf8mb4) COLLATE utf8mb4_unicode_ci AS factor_key
      </foreach>) requested WHERE EXISTS (
      SELECT 1 FROM lp_factor_identity f
      JOIN lp_factor_monthly_price p ON p.factor_identity_id=f.id
      JOIN lp_factor_upload_batch b ON b.id=p.source_upload_batch_id
      WHERE f.canonical_factor_key=requested.factor_key AND f.business_unit_type=#{unit}
        AND p.price_org_code=#{org} AND p.status='PUBLISHED'
        AND b.import_type='FINANCE_BASE' AND b.status='PUBLISHED')</script>
    """)
  Set<String> managedFactors(@Param("unit") String unit,@Param("org") String org,@Param("keys") Collection<String> keys);

  /** Preserve the requested identity alongside the raw database-matched row (0900_ai_ci). */
  final class ScrapMapping extends MaterialScrapRef {
    private String requestedMaterialCode;
    public String getRequestedMaterialCode(){return requestedMaterialCode;}
    public void setRequestedMaterialCode(String code){requestedMaterialCode=code;}
    public MaterialScrapRef reference(){var result=new MaterialScrapRef();org.springframework.beans.BeanUtils.copyProperties(this,result);return result;}
  }

  @Select("""
    <script>SELECT r.*, requested.material_code AS requested_material_code FROM (
      <foreach collection="codes" item="code" separator=" UNION ALL ">
        SELECT CONVERT(#{code} USING utf8mb4) COLLATE utf8mb4_0900_ai_ci AS material_code
      </foreach>) requested JOIN lp_material_scrap_ref r ON r.material_code=requested.material_code
      WHERE r.business_unit_type=#{unit} AND (r.effective_from IS NULL OR r.effective_from&lt;=#{date})
        AND (r.effective_to IS NULL OR r.effective_to&gt;=#{date}) ORDER BY requested.material_code,r.id</script>
    """)
  List<ScrapMapping> scrapReferences(@Param("unit") String unit,@Param("date") LocalDate date,
      @Param("codes") Collection<String> codes);

  @Select("""
    <script>SELECT id,price_org_code,publication_revision,default_scheme_code,parser_version,published_at,
      publication_note,technical_version_id,technical_result_json,batch_no,import_type,import_purpose,
      effective_strategy,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,
      factor_sheet_count,linked_sheet_count,factor_row_count,linked_row_count,auto_binding_count,warning_count,
      error_count,error_message,started_at,finished_at,created_at,updated_at
      FROM lp_factor_upload_batch WHERE business_unit_type=#{unit} AND id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach> ORDER BY id</script>
    """)
  List<FactorUploadBatch> batches(@Param("unit") String unit,@Param("ids") Collection<Long> ids);

  @Select("""
    <script>SELECT r.* FROM lp_factor_row_ref r JOIN lp_factor_identity f ON f.id=r.factor_identity_id
      WHERE f.business_unit_type=#{unit} AND r.factor_monthly_price_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach> ORDER BY r.id</script>
    """)
  List<FactorRowRef> priceReferences(@Param("unit") String unit,@Param("ids") Collection<Long> ids);
}
