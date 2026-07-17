package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MakePartPriceCalcRowMapper extends BaseMapper<MakePartPriceCalcRow> {

  @Select({
      "<script>",
      "SELECT calc_batch_id",
      "FROM lp_make_part_price_calc_row",
      "WHERE 1=1",
      "<if test='oaNo != null and oaNo != \"\"'>",
      "  AND oa_no = #{oaNo}",
      "</if>",
      "<if test='businessUnitType != null and businessUnitType != \"\"'>",
      "  AND business_unit_type = #{businessUnitType}",
      "</if>",
      "<if test='parentMaterialNo != null and parentMaterialNo != \"\"'>",
      "  AND parent_material_no = #{parentMaterialNo}",
      "</if>",
      "ORDER BY created_at DESC, id DESC",
      "LIMIT 1",
      "</script>"
  })
  String selectLatestBatchId(
      @Param("oaNo") String oaNo,
      @Param("businessUnitType") String businessUnitType,
      @Param("parentMaterialNo") String parentMaterialNo);

  @Select({
      "SELECT *",
      "FROM lp_make_part_price_calc_row",
      "WHERE oa_no = #{oaNo}",
      "  AND business_unit_type = #{businessUnitType}",
      "  AND pricing_month = #{pricingMonth}",
      "  AND parent_material_no = #{parentMaterialNo}",
      "  AND calc_batch_id = #{calcBatchId}",
      "ORDER BY created_at DESC, id DESC"
  })
  List<MakePartPriceCalcRow> selectBatchRows(
      @Param("oaNo") String oaNo,
      @Param("businessUnitType") String businessUnitType,
      @Param("pricingMonth") String pricingMonth,
      @Param("parentMaterialNo") String parentMaterialNo,
      @Param("calcBatchId") String calcBatchId);
}
