package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.dto.BomManualSummaryRow;
import com.sanhua.marketingcost.entity.BomManualItem;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BomManualItemMapper extends BaseMapper<BomManualItem> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<BomManualItem> selectList(@Param("ew") Wrapper<BomManualItem> queryWrapper);

  // --- 下方自定义 @Select 方法，均查询 lp_bom_manual_item，V21 全部加 @DataScope ---
  @Select("""
      <script>
      SELECT COUNT(1) FROM (
        SELECT 1
        FROM lp_bom_manual_item
        <where>
          deleted = 0
          <if test="bomCode != null and bomCode != ''">
            AND bom_code LIKE CONCAT('%', #{bomCode}, '%')
          </if>
          <if test="itemCode != null and itemCode != ''">
            AND item_code LIKE CONCAT('%', #{itemCode}, '%')
          </if>
          <if test="parentCode != null and parentCode != ''">
            AND parent_code LIKE CONCAT('%', #{parentCode}, '%')
          </if>
          <if test="bomLevel != null">
            AND bom_level = #{bomLevel}
          </if>
          <if test="shapeAttr != null and shapeAttr != ''">
            AND shape_attr = #{shapeAttr}
          </if>
        </where>
        GROUP BY bom_code
      ) t
      </script>
      """)
  @DataScope
  Long countSummaryRows(
      @Param("bomCode") String bomCode,
      @Param("itemCode") String itemCode,
      @Param("parentCode") String parentCode,
      @Param("bomLevel") Integer bomLevel,
      @Param("shapeAttr") String shapeAttr);

  @Select("""
      <script>
      SELECT
        bom_code AS bomCode,
        COUNT(1) AS detailCount,
        MAX(updated_at) AS updatedAt
      FROM lp_bom_manual_item
      <where>
        deleted = 0
        <if test="bomCode != null and bomCode != ''">
          AND bom_code LIKE CONCAT('%', #{bomCode}, '%')
        </if>
        <if test="itemCode != null and itemCode != ''">
          AND item_code LIKE CONCAT('%', #{itemCode}, '%')
        </if>
        <if test="parentCode != null and parentCode != ''">
          AND parent_code LIKE CONCAT('%', #{parentCode}, '%')
        </if>
        <if test="bomLevel != null">
          AND bom_level = #{bomLevel}
        </if>
        <if test="shapeAttr != null and shapeAttr != ''">
          AND shape_attr = #{shapeAttr}
        </if>
      </where>
      GROUP BY bom_code
      ORDER BY bom_code ASC
      LIMIT #{offset}, #{pageSize}
      </script>
      """)
  @DataScope
  List<BomManualSummaryRow> selectSummaryRows(
      @Param("bomCode") String bomCode,
      @Param("itemCode") String itemCode,
      @Param("parentCode") String parentCode,
      @Param("bomLevel") Integer bomLevel,
      @Param("shapeAttr") String shapeAttr,
      @Param("offset") long offset,
      @Param("pageSize") long pageSize);

  @Select("""
      <script>
      SELECT
        id,
        bom_code AS bomCode,
        item_code AS itemCode,
        item_name AS itemName,
        item_spec AS itemSpec,
        item_model AS itemModel,
        bom_level AS bomLevel,
        parent_code AS parentCode,
        shape_attr AS shapeAttr,
        bom_qty AS bomQty,
        material,
        source,
        created_at AS createdAt,
        updated_at AS updatedAt
      FROM lp_bom_manual_item
      WHERE bom_code = #{bomCode}
        AND deleted = 0
      <if test="itemCode != null and itemCode != ''">
        AND item_code LIKE CONCAT('%', #{itemCode}, '%')
      </if>
      <if test="parentCode != null and parentCode != ''">
        AND parent_code LIKE CONCAT('%', #{parentCode}, '%')
      </if>
      <if test="bomLevel != null">
        AND bom_level = #{bomLevel}
      </if>
      <if test="shapeAttr != null and shapeAttr != ''">
        AND shape_attr = #{shapeAttr}
      </if>
      ORDER BY bom_level ASC, id ASC
      </script>
      """)
  @DataScope
  List<BomManualItem> selectByBomCodeWithFilters(
      @Param("bomCode") String bomCode,
      @Param("itemCode") String itemCode,
      @Param("parentCode") String parentCode,
      @Param("bomLevel") Integer bomLevel,
      @Param("shapeAttr") String shapeAttr);
}
