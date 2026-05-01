package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CostRunPartItemMapper extends BaseMapper<CostRunPartItem> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<CostRunPartItem> selectList(@Param("ew") Wrapper<CostRunPartItem> queryWrapper);

  /**
   * 拉取试算所需的部品基础数据 —— 只查 BOM 结算行 + 物料主档，不预 JOIN 路由表。
   *
   * <p>T06.5 重构：原本 LEFT JOIN {@code lp_material_price_type t3} 喂 6 个路由字段
   * （priceType/materialShape/priority/effectiveFrom/To/sourceSystem）。
   * 但 T03/T04 后 Router 服务自己查路由，JOIN 只剩"喂 priceType 给 JJB 导出"一个用途，
   * 副作用是按候选路由数 fan-out 行数（一个料号 N 个路由 → 复制 N 行）。
   * 现在 service 层 applyResults 用 Router 命中的 PriceTypeRoute 回填这 6 个字段，
   * SQL 退回 BOM × 主档严格 1:1 关系。
   *
   * <p>表与字段：
   * <ul>
   *   <li>{@code lp_bom_costing_row t1}：BOM 结算行（每个 OA × 顶层产品 × 结算料号 1 行）</li>
   *   <li>{@code lp_material_master t2}：物料主档（U9 ItemMaster），shape_attr 是权威源</li>
   *   <li>{@code business_unit_type}：V21 数据隔离，{@code BusinessUnitInterceptor} 注入</li>
   * </ul>
   */
  @Select(
      """
          SELECT
            t1.oa_no AS oaNo,
            t2.material_name AS partName,
            t1.material_code AS partCode,
            t1.top_product_code AS productCode,
            t2.drawing_no AS partDrawingNo,
            t1.qty_per_top AS partQty,
            t2.shape_attr AS shapeAttr,
            t2.material AS material
          FROM lp_bom_costing_row t1
          LEFT JOIN lp_material_master t2
            ON t1.material_code = t2.material_code
          WHERE t1.oa_no = #{oaNo}
          ORDER BY t1.id
          """)
  @DataScope(alias = "t1")
  List<CostRunPartItemDto> selectBaseByOaNo(@Param("oaNo") String oaNo);
}
