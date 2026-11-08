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
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED'
      <choose>
        <when test="sourceMonth != null">
          AND b.source_month &lt;= #{sourceMonth}
          <if test="material != null">AND (p.material_code=#{material} OR p.specification=#{material})</if>
          AND NOT EXISTS (
            SELECT 1 FROM lp_household_inquiry_price newer
            JOIN lp_household_inquiry_import_batch nb ON nb.id=newer.import_batch_id
            WHERE newer.business_unit_type=p.business_unit_type AND newer.match_key=p.match_key
              AND nb.status='PUBLISHED' AND nb.source_month &lt;= #{sourceMonth}
              AND (nb.source_month &gt; b.source_month OR (nb.source_month=b.source_month AND newer.revision_no &gt; p.revision_no))
          )
        </when>
        <otherwise>
          AND p.current_match IS NOT NULL
          <if test="material != null">AND (p.material_code=#{material} OR p.specification=#{material})</if>
        </otherwise>
      </choose>
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
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.current_flag=0 AND p.match_key IN
      <foreach collection="materials" item="material" open="(" separator="," close=")">#{material}</foreach>
      AND p.record_fingerprint IN
      <foreach collection="fingerprints" item="hash" open="(" separator="," close=")">#{hash}</foreach>
      </script>
      """)
  List<Map<String,Object>> historicalFingerprints(@Param("unit") String unit,@Param("materials") Collection<String> materials,@Param("fingerprints") Collection<String> fingerprints);

  @Select("<script>SELECT p.*,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256 "
      + LIST_FROM + " ORDER BY <choose><when test='sourceMonth == null'>p.current_match</when><otherwise>p.match_key</otherwise></choose> LIMIT #{size} OFFSET #{offset}</script>")
  List<HouseholdInquiryPriceRow> page(@Param("unit") String unit,@Param("material") String material,@Param("sourceMonth") String sourceMonth,@Param("size") int size,@Param("offset") int offset);

  @Select("<script>SELECT COUNT(*) " + LIST_FROM + "</script>")
  long count(@Param("unit") String unit,@Param("material") String material,@Param("sourceMonth") String sourceMonth);

  @Select("""
      SELECT p.*,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.id=#{id}
      """)
  HouseholdInquiryPriceRow price(@Param("unit") String unit,@Param("id") long id);

  @Select("""
      SELECT p.*,b.source_month,b.pricing_unit,b.currency,b.tax_basis,b.file_key,b.file_sha256
      FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.match_key=#{material}
      ORDER BY p.revision_no DESC LIMIT #{size} OFFSET #{offset}
      """)
  List<HouseholdInquiryPriceRow> historyPage(@Param("unit") String unit,@Param("material") String material,@Param("size") int size,@Param("offset") int offset);

  @Select("""
      SELECT COUNT(*) FROM lp_household_inquiry_price p JOIN lp_household_inquiry_import_batch b ON b.id=p.import_batch_id
      WHERE p.business_unit_type=#{unit} AND b.status='PUBLISHED' AND p.match_key=#{material}
      """)
  long historyCount(@Param("unit") String unit,@Param("material") String material);

  @Select("SELECT * FROM lp_household_inquiry_import_batch WHERE business_unit_type=#{unit} AND id=#{id}")
  HouseholdInquiryBatchRow batch(@Param("unit") String unit,@Param("id") long id);
}
