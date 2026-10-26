package com.sanhua.marketingcost.mapper.household;

import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryBatchRow;
import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryPriceRow;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** All public reads are business-unit scoped and expose only published source versions. */
@Mapper
public interface HouseholdInquiryQueryMapper {
  @Select("SELECT * FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND publish_request_id=#{request}")
  HouseholdInquiryBatchRow batchByRequest(@Param("unit") String unit,@Param("request") String request);

  @Update("UPDATE lp_household_inquiry_import_batch SET status='CANCELLED' WHERE business_unit_type=#{unit} AND id=#{id} AND status='PREVIEW'")
  int cancel(@Param("unit") String unit,@Param("id") long id);

  @Select("SELECT * FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND preview_id=#{preview} FOR UPDATE")
  HouseholdInquiryBatchRow lockBatch(@Param("unit") String unit,@Param("preview") String preview);

  @Select("""
      <script>
      SELECT * FROM lp_household_inquiry_price FORCE INDEX (uk_household_current)
      WHERE business_unit_type=#{unit} AND current_material IN
      <foreach collection="materials" item="material" open="(" separator="," close=")">#{material}</foreach>
      ORDER BY current_material FOR UPDATE
      </script>
      """)
  List<HouseholdInquiryPriceRow> lockCurrent(@Param("unit") String unit,@Param("materials") Collection<String> materials);

  @Select("""
      <script>
      SELECT DISTINCT p.material_code,p.record_fingerprint FROM lp_household_inquiry_price p
      JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.current_flag=0 AND p.material_code IN
      <foreach collection="materials" item="material" open="(" separator="," close=")">#{material}</foreach>
      AND p.record_fingerprint IN
      <foreach collection="fingerprints" item="hash" open="(" separator="," close=")">#{hash}</foreach>
      </script>
      """)
  List<Map<String,Object>> historicalFingerprints(@Param("unit") String unit,@Param("materials") Collection<String> materials,@Param("fingerprints") Collection<String> fingerprints);

  @Select("""
      <script>
      SELECT p.*,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p FORCE INDEX (uk_household_current)
      STRAIGHT_JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.current_material IS NOT NULL
      <if test="material != null">AND p.current_material=#{material}</if>
      ORDER BY p.current_material LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<HouseholdInquiryPriceRow> page(@Param("unit") String unit,@Param("material") String material,@Param("size") int size,@Param("offset") int offset);

  @Select("""
      <script>
      SELECT COUNT(*) FROM lp_household_inquiry_price p FORCE INDEX (uk_household_current)
      STRAIGHT_JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.current_material IS NOT NULL
      <if test="material != null">AND p.current_material=#{material}</if>
      </script>
      """)
  long count(@Param("unit") String unit,@Param("material") String material);

  @Select("""
      SELECT p.*,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.id=#{id}
      """)
  HouseholdInquiryPriceRow price(@Param("unit") String unit,@Param("id") long id);

  @Select("""
      SELECT p.*,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.material_code=#{material}
      ORDER BY p.revision_no DESC LIMIT #{size} OFFSET #{offset}
      """)
  List<HouseholdInquiryPriceRow> historyPage(@Param("unit") String unit,@Param("material") String material,@Param("size") int size,@Param("offset") int offset);

  @Select("""
      SELECT COUNT(*) FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.material_code=#{material}
      """)
  long historyCount(@Param("unit") String unit,@Param("material") String material);

  @Select("SELECT * FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND id=#{id}")
  HouseholdInquiryBatchRow batch(@Param("unit") String unit,@Param("id") long id);
}
