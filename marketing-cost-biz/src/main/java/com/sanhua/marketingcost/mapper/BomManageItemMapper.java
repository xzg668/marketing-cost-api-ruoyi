package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.dto.BomManageParentRow;
import com.sanhua.marketingcost.entity.BomManageItem;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * BOM 管理表 Mapper。
 *
 * <p>T5.5：老表 {@code lp_bom_manage_item} 进入保留期（物理保留 3~6 个月用于回滚），
 * 下面所有自定义 {@code @Select} 已切换到新表 {@code lp_bom_costing_row} 读取数据，
 * 通过 LEFT JOIN {@code oa_form} / {@code oa_form_item} 补齐老表 legacy 字段
 * （4 金属价 / 产品名 / 规格 / 客户名等）。
 *
 * <p>字段映射（老 → 新）：
 * <ul>
 *   <li>{@code material_no}           → {@code top_product_code}</li>
 *   <li>{@code item_code}             → {@code material_code}</li>
 *   <li>{@code item_name/spec/model}  → {@code material_name/spec/spec}（model/spec 在 Excel 常同值）</li>
 *   <li>{@code bom_qty}               → {@code qty_per_top}</li>
 *   <li>{@code bom_code/root_item_code} → 新表无此概念，统一填 {@code top_product_code}</li>
 *   <li>{@code material/source/filter_rule} → 新表不承接，固定返 NULL</li>
 * </ul>
 *
 * <p>V21：主表 {@code lp_bom_costing_row} 别名 {@code t1}；自定义方法统一加
 * {@code @DataScope(alias = "t1")} 让拦截器按 {@code t1.business_unit_type} 注入隔离条件。
 */
public interface BomManageItemMapper extends BaseMapper<BomManageItem> {

  /** V21：selectList 走数据隔离（按 business_unit_type 过滤） */
  @DataScope
  @Override
  List<BomManageItem> selectList(@Param("ew") Wrapper<BomManageItem> queryWrapper);

  // --- 下方自定义 @Select 方法 ---
  // T5.5：切到新表 lp_bom_costing_row，V21 统一按 t1.business_unit_type 过滤。

  /**
   * 统计父行数量 —— 按 {@code (oa_no, top_product_code)} 聚合。
   *
   * <p>{@code bomCode} 参数在新表无对应列，改对 {@code top_product_code} 做模糊匹配（语义兼容）。
   */
  @Select("""
      <script>
      SELECT COUNT(1) FROM (
        SELECT 1
        FROM lp_bom_costing_row t1
        <where>
          <if test="oaNo != null and oaNo != ''">
            AND t1.oa_no LIKE CONCAT('%', #{oaNo}, '%')
          </if>
          <if test="bomCode != null and bomCode != ''">
            AND t1.top_product_code LIKE CONCAT('%', #{bomCode}, '%')
          </if>
          <if test="materialNo != null and materialNo != ''">
            AND t1.top_product_code LIKE CONCAT('%', #{materialNo}, '%')
          </if>
          <if test="shapeAttr != null and shapeAttr != ''">
            AND t1.shape_attr = #{shapeAttr}
          </if>
        </where>
        GROUP BY t1.oa_no, t1.top_product_code
      ) t
      </script>
      """)
  @DataScope(alias = "t1")
  Long countParentRows(
      @Param("oaNo") String oaNo,
      @Param("bomCode") String bomCode,
      @Param("materialNo") String materialNo,
      @Param("shapeAttr") String shapeAttr);

  /**
   * 查父行列表 —— 按 {@code (oa_no, top_product_code)} 聚合，LEFT JOIN oa_form/oa_form_item
   * 补齐 4 金属价 / 产品名 / 规格 / 客户名等老表 legacy 字段。
   *
   * <p>聚合函数说明：一个 (oa_no, top_product_code) 分组可能关联多条 {@code oa_form_item}
   * （业务上通常 1:1），这里用 {@code MIN} 取稳定第一条，行为与老表按 id 第一条接近。
   */
  @Select("""
      <script>
      SELECT
        MIN(t3.id) AS oaFormItemId,
        t1.oa_no AS oaNo,
        MIN(t2.id) AS oaFormId,
        t1.top_product_code AS materialNo,
        MIN(t3.product_name) AS productName,
        MIN(t3.spec) AS productSpec,
        MIN(t3.sunl_model) AS productModel,
        MIN(t2.customer) AS customerName,
        MIN(t2.copper_price) AS copperPriceTax,
        MIN(t2.zinc_price) AS zincPriceTax,
        MIN(t2.aluminum_price) AS aluminumPriceTax,
        MIN(t2.steel_price) AS steelPriceTax,
        t1.top_product_code AS bomCode,
        t1.top_product_code AS rootItemCode,
        COUNT(1) AS detailCount,
        MAX(t1.updated_at) AS updatedAt
      FROM lp_bom_costing_row t1
      LEFT JOIN oa_form t2
        ON t2.oa_no = t1.oa_no AND t2.deleted = 0
      LEFT JOIN oa_form_item t3
        ON t3.oa_form_id = t2.id
       AND t3.material_no = t1.top_product_code
       AND t3.deleted = 0
      <where>
        <if test="oaNo != null and oaNo != ''">
          AND t1.oa_no LIKE CONCAT('%', #{oaNo}, '%')
        </if>
        <if test="bomCode != null and bomCode != ''">
          AND t1.top_product_code LIKE CONCAT('%', #{bomCode}, '%')
        </if>
        <if test="materialNo != null and materialNo != ''">
          AND t1.top_product_code LIKE CONCAT('%', #{materialNo}, '%')
        </if>
        <if test="shapeAttr != null and shapeAttr != ''">
          AND t1.shape_attr = #{shapeAttr}
        </if>
      </where>
      GROUP BY t1.oa_no, t1.top_product_code
      ORDER BY t1.top_product_code ASC, t1.oa_no ASC
      LIMIT #{offset}, #{pageSize}
      </script>
      """)
  @DataScope(alias = "t1")
  List<BomManageParentRow> selectParentRows(
      @Param("oaNo") String oaNo,
      @Param("bomCode") String bomCode,
      @Param("materialNo") String materialNo,
      @Param("shapeAttr") String shapeAttr,
      @Param("offset") long offset,
      @Param("pageSize") long pageSize);

  /**
   * 查某父行下明细 —— 以 {@code (oa_no, top_product_code)} 定位，LEFT JOIN oa_form / oa_form_item
   * 补齐 legacy 展示字段，新表不承接的字段返 NULL。
   *
   * <p>参数兼容处理：
   * <ul>
   *   <li>{@code oaFormItemId} —— 新表不分 oa_form_item 维度，参数保留但 SQL 不用</li>
   *   <li>{@code bomCode}      —— 新表无 bom_code，参数保留但 SQL 不用</li>
   *   <li>{@code rootItemCode} —— 等价于 {@code top_product_code}，用于定位顶层产品</li>
   * </ul>
   */
  @Select("""
      <script>
      SELECT
        t1.id,
        t1.oa_no AS oaNo,
        t2.id AS oaFormId,
        t3.id AS oaFormItemId,
        t1.top_product_code AS materialNo,
        t3.product_name AS productName,
        t3.spec AS productSpec,
        t3.sunl_model AS productModel,
        t2.customer AS customerName,
        t2.copper_price AS copperPriceTax,
        t2.zinc_price AS zincPriceTax,
        t2.aluminum_price AS aluminumPriceTax,
        t2.steel_price AS steelPriceTax,
        t1.top_product_code AS bomCode,
        t1.top_product_code AS rootItemCode,
        t1.material_code AS itemCode,
        t1.material_name AS itemName,
        t1.material_spec AS itemSpec,
        t1.material_spec AS itemModel,
        t1.shape_attr AS shapeAttr,
        t1.qty_per_top AS bomQty,
        NULL AS material,
        NULL AS source,
        NULL AS filterRule,
        t1.created_at AS createdAt,
        t1.updated_at AS updatedAt
      FROM lp_bom_costing_row t1
      LEFT JOIN oa_form t2
        ON t2.oa_no = t1.oa_no AND t2.deleted = 0
      LEFT JOIN oa_form_item t3
        ON t3.oa_form_id = t2.id
       AND t3.material_no = t1.top_product_code
       AND t3.deleted = 0
      WHERE t1.oa_no = #{oaNo}
        AND t1.top_product_code = #{rootItemCode}
      <if test="shapeAttr != null and shapeAttr != ''">
        AND t1.shape_attr = #{shapeAttr}
      </if>
      ORDER BY t1.material_code ASC, t1.id ASC
      </script>
      """)
  @DataScope(alias = "t1")
  List<BomManageItem> selectDetailRows(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("bomCode") String bomCode,
      @Param("rootItemCode") String rootItemCode,
      @Param("shapeAttr") String shapeAttr);
}
