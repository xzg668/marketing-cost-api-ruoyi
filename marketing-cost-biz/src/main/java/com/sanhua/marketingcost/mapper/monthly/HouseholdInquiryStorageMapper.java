package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryBatchRow;
import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryPriceRow;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface HouseholdInquiryStorageMapper {
  @Insert("""
      INSERT INTO lp_household_inquiry_import_batch(business_unit_type,preview_id,publish_request_id,
        preview_fingerprint,file_key,file_sha256,file_name,worksheet_name,parser_schema,source_month,
        source_month_origin,pricing_unit,currency,tax_basis,preview_json,status,expires_at,created_by)
      VALUES(#{businessUnitType},#{previewId},#{publishRequestId},#{previewFingerprint},#{fileKey},#{fileSha256},
        #{fileName},#{worksheetName},#{parserSchema},#{sourceMonth},#{sourceMonthOrigin},#{pricingUnit},
        #{currency},#{taxBasis},#{previewJson},#{status},#{expiresAt},#{createdBy})
      """)
  @Options(useGeneratedKeys=true,keyProperty="id")
  int insertBatch(HouseholdInquiryBatchRow row);

  @Select("SELECT * FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND preview_id=#{preview}")
  HouseholdInquiryBatchRow batchByPreview(@Param("unit") String unit,@Param("preview") String preview);

  @Select("SELECT * FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND id=#{id}")
  HouseholdInquiryBatchRow batchById(@Param("unit") String unit,@Param("id") long id);

  @Insert("""
      INSERT INTO lp_household_inquiry_price(business_unit_type,material_code,material_name,specification,
        revision_no,previous_price_id,import_batch_id,source_rows_json,unit_price,household_rate,
        pricing_fingerprint,record_fingerprint,reference_json,current_flag)
      VALUES(#{businessUnitType},#{materialCode},#{materialName},#{specification},#{revisionNo},#{previousPriceId},
        #{importBatchId},#{sourceRowsJson},#{unitPrice},#{householdRate},#{pricingFingerprint},
        #{recordFingerprint},#{referenceJson},#{currentFlag})
      """)
  @Options(useGeneratedKeys=true,keyProperty="id")
  int insertPrice(HouseholdInquiryPriceRow row);

  @Update("UPDATE lp_household_inquiry_price SET current_flag=0 WHERE business_unit_type=#{unit} AND id=#{id} AND current_flag=1")
  int retire(@Param("unit") String unit,@Param("id") long id);

  @Select("""
      <script>
      SELECT p.*,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.current_material IN
      <foreach collection="materials" item="material" open="(" separator="," close=")">#{material}</foreach>
      ORDER BY p.material_code
      </script>
      """)
  List<HouseholdInquiryPriceRow> current(@Param("unit") String unit,@Param("materials") Collection<String> materials);

  @Select("""
      SELECT p.*,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.material_code=#{material} ORDER BY p.revision_no DESC
      """)
  List<HouseholdInquiryPriceRow> history(@Param("unit") String unit,@Param("material") String material);

  /** The publishing transaction may read its just-inserted row before the batch becomes visible. */
  @Select("""
      SELECT p.*,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND p.id=#{id}
      """)
  HouseholdInquiryPriceRow stagedPriceById(@Param("unit") String unit,@Param("id") long id);

  @Update("""
      UPDATE lp_household_inquiry_import_batch SET status='PUBLISHED',result_json=#{result},published_at=CURRENT_TIMESTAMP(6)
      WHERE business_unit_type=#{unit} AND id=#{id} AND status='PREVIEW'
      """)
  int completePublication(@Param("unit") String unit,@Param("id") long id,@Param("result") String result);
}
