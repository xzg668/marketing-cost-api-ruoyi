package com.sanhua.marketingcost.mapper.household;

import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryBatchRow;
import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryPriceRow;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** All public reads are business-unit scoped and expose only published source versions. */
@Mapper
public interface HouseholdInquiryQueryMapper {
  // For each code/model identity, prefer the selected month, otherwise the nearest earlier month.
  String LIST_FROM = """
      FROM lp_household_inquiry_price p
      <if test="sourceMonth == null">FORCE INDEX (uk_household_current_match)</if>
      STRAIGHT_JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.row_voided=0
      <choose>
        <when test="sourceMonth != null">
          AND b.source_month &lt;= #{sourceMonth}
          <if test="material != null">AND (p.material_code=#{material} OR p.specification=#{material})</if>
          AND NOT EXISTS (
            SELECT 1 FROM lp_household_inquiry_price newer
            JOIN lp_household_inquiry_import_batch nb ON nb.id=newer.import_batch_id
            WHERE newer.business_unit_type=p.business_unit_type AND newer.match_key=p.match_key
              AND nb.status IN ('PUBLISHED','VOIDED') AND nb.source_month &lt;= #{sourceMonth}
              AND (nb.source_month &gt; b.source_month OR (nb.source_month=b.source_month AND newer.revision_no &gt; p.revision_no))
          )
        </when>
        <otherwise>
          AND p.current_match IS NOT NULL
          <if test="material != null">AND (p.material_code=#{material} OR p.specification=#{material})</if>
        </otherwise>
      </choose>
      <if test="model != null and model != ''">AND LOCATE(#{model},LOWER(COALESCE(p.specification,''))) &gt; 0</if>
      """;
  @Select("SELECT * FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND publish_request_id=#{request}")
  HouseholdInquiryBatchRow batchByRequest(@Param("unit") String unit,@Param("request") String request);

  @Update("UPDATE lp_household_inquiry_import_batch SET status='CANCELLED' WHERE business_unit_type=#{unit} AND id=#{id} AND status='PREVIEW'")
  int cancel(@Param("unit") String unit,@Param("id") long id);

  @Select("SELECT * FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND preview_id=#{preview} FOR UPDATE")
  HouseholdInquiryBatchRow lockBatch(@Param("unit") String unit,@Param("preview") String preview);

  @Select("""
      <script>
      SELECT * FROM lp_household_inquiry_price FORCE INDEX (uk_household_current_match)
      WHERE business_unit_type=#{unit} AND current_match IN
      <foreach collection="materials" item="material" open="(" separator="," close=")">#{material}</foreach>
      ORDER BY current_match FOR UPDATE
      </script>
      """)
  List<HouseholdInquiryPriceRow> lockCurrent(@Param("unit") String unit,@Param("materials") Collection<String> materials);

  @Select("""
      <script>
      SELECT DISTINCT p.match_key,p.record_fingerprint FROM lp_household_inquiry_price p
      JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.row_voided=0 AND p.current_flag=0 AND p.match_key IN
      <foreach collection="materials" item="material" open="(" separator="," close=")">#{material}</foreach>
      AND p.record_fingerprint IN
      <foreach collection="fingerprints" item="hash" open="(" separator="," close=")">#{hash}</foreach>
      </script>
      """)
  List<Map<String,Object>> historicalFingerprints(@Param("unit") String unit,@Param("materials") Collection<String> materials,@Param("fingerprints") Collection<String> fingerprints);

  @Select("<script>SELECT p.*,CASE WHEN p.row_voided=1 THEN 'VOIDED' ELSE b.status END AS batch_status,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256 "
      + LIST_FROM + " ORDER BY <choose><when test='sourceMonth == null'>p.current_match</when><otherwise>p.match_key</otherwise></choose> LIMIT #{size} OFFSET #{offset}</script>")
  List<HouseholdInquiryPriceRow> page(@Param("unit") String unit,@Param("material") String material,@Param("sourceMonth") String sourceMonth,@Param("size") int size,@Param("offset") int offset,@Param("model") String model);

  @Select("<script>SELECT COUNT(*) " + LIST_FROM + "</script>")
  long count(@Param("unit") String unit,@Param("material") String material,@Param("sourceMonth") String sourceMonth,@Param("model") String model);

  @Select("""
      SELECT p.*,CASE WHEN p.row_voided=1 THEN 'VOIDED' ELSE b.status END AS batch_status,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status IN ('PUBLISHED','VOIDED') AND p.id=#{id}
      """)
  HouseholdInquiryPriceRow price(@Param("unit") String unit,@Param("id") long id);

  @Select("""
      SELECT p.*,CASE WHEN p.row_voided=1 THEN 'VOIDED' ELSE b.status END AS batch_status,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status IN ('PUBLISHED','VOIDED') AND p.match_key=#{material}
      ORDER BY p.revision_no DESC LIMIT #{size} OFFSET #{offset}
      """)
  List<HouseholdInquiryPriceRow> historyPage(@Param("unit") String unit,@Param("material") String material,@Param("size") int size,@Param("offset") int offset);

  @Select("""
      SELECT COUNT(*) FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status IN ('PUBLISHED','VOIDED') AND p.match_key=#{material}
      """)
  long historyCount(@Param("unit") String unit,@Param("material") String material);

  @Select("""
      SELECT id,file_name,source_month AS month,status,published_at AS imported_at,created_by AS imported_by,
        voided_at,voided_by,(SELECT COUNT(*) FROM lp_household_inquiry_price p WHERE p.import_batch_id=lp_household_inquiry_import_batch.id) AS row_count
      FROM lp_household_inquiry_import_batch
      WHERE business_unit_type=#{unit} AND status IN ('PUBLISHED','VOIDED')
      ORDER BY published_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
      """)
  List<Map<String,Object>> imports(@Param("unit") String unit,@Param("size") int size,@Param("offset") int offset);

  @Select("SELECT COUNT(*) FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND status IN ('PUBLISHED','VOIDED')")
  long importCount(@Param("unit") String unit);

  @Select("SELECT match_key FROM lp_household_inquiry_price WHERE business_unit_type=#{unit} AND import_batch_id=#{id} ORDER BY match_key")
  List<String> batchKeys(@Param("unit") String unit,@Param("id") long id);

  @Select("SELECT id FROM lp_household_inquiry_price WHERE business_unit_type=#{unit} AND import_batch_id=#{id}")
  List<Long> batchSourceIds(@Param("unit") String unit,@Param("id") long id);

  @Select("SELECT CASE WHEN p.row_voided=1 THEN 'VOIDED' ELSE b.status END FROM lp_household_inquiry_import_batch b JOIN lp_household_inquiry_price p ON p.import_batch_id=b.id WHERE p.business_unit_type=#{unit} AND p.id=#{id}")
  String priceBatchStatus(@Param("unit") String unit,@Param("id") long id);

  @Update("UPDATE lp_household_inquiry_price SET row_voided=1 WHERE business_unit_type=#{unit} AND id=#{id} AND row_voided=0")
  int voidRow(@Param("unit") String unit,@Param("id") long id);

  @Update("""
      UPDATE lp_household_inquiry_import_batch SET status='VOIDED',voided_by=#{actor},voided_at=CURRENT_TIMESTAMP(6)
      WHERE business_unit_type=#{unit} AND id=#{id} AND status='PUBLISHED'
      """)
  int voidBatch(@Param("unit") String unit,@Param("id") long id,@Param("actor") String actor);

  @Select("SELECT * FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND id=#{id}")
  HouseholdInquiryBatchRow batch(@Param("unit") String unit,@Param("id") long id);
}
