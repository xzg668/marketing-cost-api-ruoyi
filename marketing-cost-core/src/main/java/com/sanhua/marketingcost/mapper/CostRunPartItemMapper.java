package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.RollupPartComponentDto;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CostRunPartItemMapper extends BaseMapper<CostRunPartItem> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<CostRunPartItem> selectList(@Param("ew") Wrapper<CostRunPartItem> queryWrapper);

  @Delete(
      """
          DELETE FROM lp_cost_run_part_item
          WHERE oa_no = #{oaNo}
            AND product_code = #{productCode}
          """)
  int deleteQuoteItems(@Param("oaNo") String oaNo, @Param("productCode") String productCode);

  @Delete(
      """
          DELETE FROM lp_cost_run_part_item
          WHERE cost_run_no = #{costRunNo}
          """)
  int deleteQuoteItemsByCostRunNo(@Param("costRunNo") String costRunNo);

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
            t1.id AS bomRowId,
            t1.oa_no AS oaNo,
            t2.material_name AS partName,
            t1.material_code AS partCode,
            t1.top_product_code AS productCode,
            t2.drawing_no AS partDrawingNo,
            t1.qty_per_top AS partQty,
            t2.shape_attr AS shapeAttr,
            t2.material AS material,
            t1.price_org_code AS priceOrgCode,
            t1.material_organization_code AS materialOrganizationCode
          FROM lp_bom_costing_row t1
          LEFT JOIN lp_material_master t2
            ON t1.material_code = t2.material_code
          WHERE t1.oa_no = #{oaNo}
          ORDER BY t1.id
          """)
  @DataScope(alias = "t1")
  List<CostRunPartItemDto> selectBaseByOaNo(@Param("oaNo") String oaNo);

  @Select(
      """
          SELECT
            t1.id AS bomRowId,
            t1.oa_no AS oaNo,
            t2.material_name AS partName,
            t1.material_code AS partCode,
            t1.top_product_code AS productCode,
            t2.drawing_no AS partDrawingNo,
            t1.qty_per_top AS partQty,
            t2.shape_attr AS shapeAttr,
            t2.material AS material,
            t1.price_org_code AS priceOrgCode,
            t1.material_organization_code AS materialOrganizationCode
          FROM lp_bom_costing_row t1
          LEFT JOIN lp_material_master t2
            ON t1.material_code = t2.material_code
          WHERE t1.oa_no = #{oaNo}
            AND t1.oa_form_item_id = #{oaFormItemId}
            AND t1.top_product_code = #{productCode}
            AND t1.period_month = #{periodMonth}
          ORDER BY t1.id
          """)
  @DataScope(alias = "t1")
  List<CostRunPartItemDto> selectBaseByQuoteScope(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("productCode") String productCode,
      @Param("periodMonth") String periodMonth);

  /**
   * 查询已存部品行对应的上卷子件成本分项。
   *
   * <p>价格分项必须沿 {@code price_prepare_item_id -> result_ref_id -> calc_batch_id} 读取，
   * 不能取制造件“最新批次”，否则历史核算版本会被后来生成的价格污染。
   */
  @Select({
    "<script>",
    "SELECT",
    "  t1.id AS partItemId,",
    "  t1.bom_row_id AS bomRowId,",
    "  sr.sub_material_code AS childMaterialCode,",
    "  COALESCE(calc.child_material_name, sr.sub_material_name) AS childMaterialName,",
    "  calc.child_material_spec AS childMaterialSpec,",
    "  sr.child_qty_per_top AS childQtyPerTop,",
    "  calc.cost_price AS childUnitCost,",
    "  calc.raw_price_type AS childRawPriceType",
    "FROM lp_cost_run_part_item t1",
    "JOIN (",
    "  SELECT",
    "    costing_row_id,",
    "    sub_material_code,",
    "    MAX(sub_material_name) AS sub_material_name,",
    "    SUM(sub_qty_per_top) AS child_qty_per_top",
    "  FROM lp_bom_costing_row_sub_ref",
    "  WHERE ref_type = 'SPECIAL_ROLLUP_CHILD'",
    "  GROUP BY costing_row_id, sub_material_code",
    ") sr ON sr.costing_row_id = t1.bom_row_id",
    "LEFT JOIN lp_price_prepare_item prep",
    "  ON prep.id = t1.price_prepare_item_id",
    " AND prep.result_ref_type = 'MAKE_PART_PRICE'",
    "LEFT JOIN lp_make_part_price_calc_row seed ON seed.id = prep.result_ref_id",
    "LEFT JOIN lp_make_part_price_calc_row calc",
    "  ON calc.calc_batch_id = seed.calc_batch_id",
    " AND calc.parent_material_no = seed.parent_material_no",
    " AND calc.child_material_no COLLATE utf8mb4_unicode_ci = sr.sub_material_code",
    "WHERE t1.id IN",
    "<foreach collection='partItemIds' item='id' open='(' separator=',' close=')'>",
    "  #{id}",
    "</foreach>",
    "ORDER BY t1.id, sr.sub_material_code, calc.id",
    "</script>"
  })
  @DataScope(alias = "t1")
  List<RollupPartComponentDto> selectRollupDisplayComponents(
      @Param("partItemIds") Collection<Long> partItemIds);
}
