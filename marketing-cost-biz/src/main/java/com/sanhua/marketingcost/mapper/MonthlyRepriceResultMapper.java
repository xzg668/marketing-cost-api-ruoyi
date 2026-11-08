package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MonthlyRepriceResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceSourceDto;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MonthlyRepriceResultMapper extends BaseMapper<MonthlyRepriceResult> {

  @Select("<script>SELECT published.* FROM ("+MonthlyRepricePublishedSql.RESULTS+") published ${ew.customSqlSegment}</script>")
  com.baomidou.mybatisplus.extension.plugins.pagination.Page<MonthlyRepriceResult> pagePublished(
      com.baomidou.mybatisplus.extension.plugins.pagination.Page<MonthlyRepriceResult> page,
      @Param("ew") com.baomidou.mybatisplus.core.conditions.Wrapper<MonthlyRepriceResult> query);

  @Select("SELECT published.* FROM ("+MonthlyRepricePublishedSql.RESULTS+") published WHERE reprice_no=#{no} AND id=#{id}")
  MonthlyRepriceResult publishedResult(@Param("no") String no,@Param("id") long id);

  @Select("""
      SELECT COUNT(*)
        FROM lp_monthly_reprice_result
       WHERE reprice_no = #{repriceNo}
      """)
  long countByRepriceNo(@Param("repriceNo") String repriceNo);

  @Select("SELECT published.* FROM ("+MonthlyRepricePublishedSql.RESULTS+") published "
      +"WHERE reuse_batch_id IS NULL AND BINARY business_unit_type=BINARY #{unit} "
      +"AND BINARY oa_no=BINARY #{oa} AND BINARY product_code=BINARY #{product} AND calc_status='SUCCESS'")
  List<MonthlyRepriceResult> publishedLegacyResults(@Param("unit") String unit,@Param("oa") String oa,@Param("product") String product);

  @Select("SELECT COUNT(*) FROM lp_monthly_reprice_source WHERE batch_id=#{batchId} AND object_id=#{objectId}")
  long countSources(@Param("batchId") long batchId, @Param("objectId") long objectId);

  @Select("""
      SELECT s.id,s.oa_no,s.oa_form_item_id,s.source_cost_version_id,
        (s.source_cost_version_id=#{selectedSource}) AS selected
      FROM lp_monthly_reprice_source s
      JOIN lp_monthly_reprice_result r ON r.id=s.object_id AND r.reuse_batch_id=s.batch_id
      WHERE s.batch_id=#{batchId} AND s.object_id=#{objectId}
      ORDER BY s.id LIMIT #{limit} OFFSET #{offset}
      """)
  List<MonthlyRepriceSourceDto> pageSources(@Param("batchId") long batchId,
      @Param("objectId") long objectId, @Param("selectedSource") Long selectedSource, @Param("offset") long offset, @Param("limit") int limit);

  @Select("""
      SELECT r.* FROM lp_monthly_reprice_source s
      JOIN ("""+MonthlyRepricePublishedSql.RESULTS+"""
        ) r ON r.id=s.object_id AND r.reuse_batch_id=s.batch_id
      JOIN lp_monthly_reprice_batch b ON b.id=r.reuse_batch_id
      WHERE s.oa_no=#{oaNo} AND BINARY s.oa_no=BINARY #{oaNo} AND s.oa_form_item_id=#{itemId}
        AND BINARY r.product_code=BINARY #{productCode} AND BINARY b.business_unit_type=BINARY #{unit}
        AND b.scope_version=1 AND b.confirmed_at IS NOT NULL AND r.calc_status='SUCCESS'
      ORDER BY r.pricing_month,r.id
      """)
  List<MonthlyRepriceResult> confirmedForSource(@Param("unit") String unit, @Param("oaNo") String oaNo,
      @Param("itemId") long itemId, @Param("productCode") String productCode);

  @Select("""
      SELECT COUNT(*) FROM lp_monthly_reprice_source
      WHERE batch_id=#{batchId} AND object_id=#{objectId}
        AND BINARY oa_no=BINARY #{oaNo} AND oa_form_item_id=#{itemId}
      """)
  long sourceMembership(@Param("batchId") long batchId, @Param("objectId") long objectId,
      @Param("oaNo") String oaNo, @Param("itemId") long itemId);

}
