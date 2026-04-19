package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CostRunPartItemMapper extends BaseMapper<CostRunPartItem> {
  @Select(
      """
          SELECT
            t1.oa_no AS oaNo,
            t2.material_name AS partName,
            t2.material_code AS partCode,
            t1.material_no AS productCode,
            t2.drawing_no AS partDrawingNo,
            t1.bom_qty AS partQty,
            t2.shape_attr AS shapeAttr,
            t2.material AS material,
            t3.price_type AS priceType
          FROM lp_bom_manage_item t1
          LEFT JOIN lp_material_master t2
            ON t1.item_code = t2.material_code
          LEFT JOIN lp_material_price_type t3
            ON t1.item_code = t3.material_code
          WHERE t1.oa_no = #{oaNo}
          ORDER BY t1.id
          """)
  List<CostRunPartItemDto> selectBaseByOaNo(@Param("oaNo") String oaNo);
}
