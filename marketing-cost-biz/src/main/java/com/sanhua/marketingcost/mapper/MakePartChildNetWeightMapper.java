package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MakePartChildNetWeight;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MakePartChildNetWeightMapper extends BaseMapper<MakePartChildNetWeight> {

  /**
   * 查父子材料在指定核算月的净重。月份和 BOM 版本精确记录优先，空值记录可作为长期默认值。
   */
  @Select({
      "<script>",
      "SELECT *",
      "FROM lp_make_part_child_net_weight",
      "WHERE material_organization_code = #{materialOrganizationCode}",
      "  AND parent_material_no = #{parentMaterialNo}",
      "  AND child_material_no = #{childMaterialNo}",
      "<choose>",
      "  <when test='bomVersion != null and bomVersion != \"\"'>",
      "    AND bom_version IN (#{bomVersion}, '')",
      "  </when>",
      "  <otherwise>",
      "    AND bom_version = ''",
      "  </otherwise>",
      "</choose>",
      "<choose>",
      "  <when test='periodMonth != null and periodMonth != \"\"'>",
      "    AND period_month IN (#{periodMonth}, '')",
      "  </when>",
      "  <otherwise>",
      "    AND period_month = ''",
      "  </otherwise>",
      "</choose>",
      "ORDER BY",
      "  CASE WHEN period_month = #{periodMonth} THEN 0 ELSE 1 END,",
      "  CASE WHEN bom_version = #{bomVersion} THEN 0 ELSE 1 END,",
      "  id DESC",
      "LIMIT 1",
      "</script>"
  })
  MakePartChildNetWeight selectEffective(
      @Param("materialOrganizationCode") String materialOrganizationCode,
      @Param("parentMaterialNo") String parentMaterialNo,
      @Param("childMaterialNo") String childMaterialNo,
      @Param("bomVersion") String bomVersion,
      @Param("periodMonth") String periodMonth);
}
