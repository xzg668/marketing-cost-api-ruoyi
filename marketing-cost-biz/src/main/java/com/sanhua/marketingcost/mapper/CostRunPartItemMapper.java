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
   * 拉取试算所需的部品基础数据 + 当前生效的取价路由。
   *
   * <p>T5.5：从老表 {@code lp_bom_manage_item} 切到新表 {@code lp_bom_costing_row}。
   * 字段映射一对一替换：
   * <ul>
   *   <li>{@code t1.material_no}       → {@code t1.top_product_code}（顶层产品料号）</li>
   *   <li>{@code t1.item_code}         → {@code t1.material_code}（结算行料号 / JOIN 键）</li>
   *   <li>{@code t1.bom_qty}           → {@code t1.qty_per_top}（累计到顶层用量）</li>
   *   <li>{@code t1.shape_attr}        → 仍由 MaterialMaster {@code t2.shape_attr} 提供</li>
   *   <li>{@code oa_no / business_unit_type} 字段语义不变</li>
   * </ul>
   *
   * <p>V10 升级后 lp_material_price_type 多了 material_shape/priority/effective_from/to/source_system，
   * 这里把 6 桶分发所需的字段全部 select 出来；Router 服务在 Java 层做最终命中（保证可单测）。
   *
   * <p>V21：主表 {@code lp_bom_costing_row} 别名 {@code t1}，{@code BusinessUnitInterceptor}
   * 按 {@code t1.business_unit_type} 注入数据隔离条件（新表带 {@code business_unit_type} 列，
   * 与老表语义一致）。
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
            t2.material AS material,
            t3.material_shape AS materialShape,
            t3.price_type AS priceType,
            t3.priority AS priority,
            t3.effective_from AS effectiveFrom,
            t3.effective_to AS effectiveTo,
            t3.source_system AS sourceSystem
          FROM lp_bom_costing_row t1
          LEFT JOIN lp_material_master t2
            ON t1.material_code = t2.material_code
          LEFT JOIN lp_material_price_type t3
            ON t1.material_code = t3.material_code
          WHERE t1.oa_no = #{oaNo}
          ORDER BY t1.id
          """)
  @DataScope(alias = "t1")
  List<CostRunPartItemDto> selectBaseByOaNo(@Param("oaNo") String oaNo);
}
