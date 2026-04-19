package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.dto.BomManageParentRow;
import com.sanhua.marketingcost.entity.BomManageItem;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BomManageItemMapper extends BaseMapper<BomManageItem> {
  @Select("""
      <script>
      SELECT COUNT(1) FROM (
        SELECT 1
        FROM lp_bom_manage_item
        <where>
          <if test="oaNo != null and oaNo != ''">
            AND oa_no LIKE CONCAT('%', #{oaNo}, '%')
          </if>
          <if test="bomCode != null and bomCode != ''">
            AND bom_code LIKE CONCAT('%', #{bomCode}, '%')
          </if>
          <if test="materialNo != null and materialNo != ''">
            AND material_no LIKE CONCAT('%', #{materialNo}, '%')
          </if>
          <if test="shapeAttr != null and shapeAttr != ''">
            AND shape_attr = #{shapeAttr}
          </if>
        </where>
        GROUP BY
          oa_form_item_id, oa_no, oa_form_id, material_no, product_name, product_spec,
          product_model, customer_name, copper_price_tax, zinc_price_tax, aluminum_price_tax,
          steel_price_tax, bom_code, root_item_code
      ) t
      </script>
      """)
  Long countParentRows(
      @Param("oaNo") String oaNo,
      @Param("bomCode") String bomCode,
      @Param("materialNo") String materialNo,
      @Param("shapeAttr") String shapeAttr);

  @Select("""
      <script>
      SELECT
        oa_form_item_id AS oaFormItemId,
        oa_no AS oaNo,
        oa_form_id AS oaFormId,
        material_no AS materialNo,
        product_name AS productName,
        product_spec AS productSpec,
        product_model AS productModel,
        customer_name AS customerName,
        copper_price_tax AS copperPriceTax,
        zinc_price_tax AS zincPriceTax,
        aluminum_price_tax AS aluminumPriceTax,
        steel_price_tax AS steelPriceTax,
        bom_code AS bomCode,
        root_item_code AS rootItemCode,
        COUNT(1) AS detailCount,
        MAX(updated_at) AS updatedAt
      FROM lp_bom_manage_item
      <where>
        <if test="oaNo != null and oaNo != ''">
          AND oa_no LIKE CONCAT('%', #{oaNo}, '%')
        </if>
        <if test="bomCode != null and bomCode != ''">
          AND bom_code LIKE CONCAT('%', #{bomCode}, '%')
        </if>
        <if test="materialNo != null and materialNo != ''">
          AND material_no LIKE CONCAT('%', #{materialNo}, '%')
        </if>
        <if test="shapeAttr != null and shapeAttr != ''">
          AND shape_attr = #{shapeAttr}
        </if>
      </where>
      GROUP BY
        oa_form_item_id, oa_no, oa_form_id, material_no, product_name, product_spec,
        product_model, customer_name, copper_price_tax, zinc_price_tax, aluminum_price_tax,
        steel_price_tax, bom_code, root_item_code
      ORDER BY bom_code ASC, oa_form_item_id ASC, root_item_code ASC
      LIMIT #{offset}, #{pageSize}
      </script>
      """)
  List<BomManageParentRow> selectParentRows(
      @Param("oaNo") String oaNo,
      @Param("bomCode") String bomCode,
      @Param("materialNo") String materialNo,
      @Param("shapeAttr") String shapeAttr,
      @Param("offset") long offset,
      @Param("pageSize") long pageSize);

  @Select("""
      <script>
      SELECT
        id,
        oa_no AS oaNo,
        oa_form_id AS oaFormId,
        oa_form_item_id AS oaFormItemId,
        material_no AS materialNo,
        product_name AS productName,
        product_spec AS productSpec,
        product_model AS productModel,
        customer_name AS customerName,
        copper_price_tax AS copperPriceTax,
        zinc_price_tax AS zincPriceTax,
        aluminum_price_tax AS aluminumPriceTax,
        steel_price_tax AS steelPriceTax,
        bom_code AS bomCode,
        root_item_code AS rootItemCode,
        item_code AS itemCode,
        item_name AS itemName,
        item_spec AS itemSpec,
        item_model AS itemModel,
        shape_attr AS shapeAttr,
        bom_qty AS bomQty,
        material,
        source,
        filter_rule AS filterRule,
        created_at AS createdAt,
        updated_at AS updatedAt
      FROM lp_bom_manage_item
      WHERE oa_no = #{oaNo}
        AND oa_form_item_id = #{oaFormItemId}
        AND bom_code = #{bomCode}
        AND root_item_code = #{rootItemCode}
      <if test="shapeAttr != null and shapeAttr != ''">
        AND shape_attr = #{shapeAttr}
      </if>
      ORDER BY item_code ASC, id ASC
      </script>
      """)
  List<BomManageItem> selectDetailRows(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("bomCode") String bomCode,
      @Param("rootItemCode") String rootItemCode,
      @Param("shapeAttr") String shapeAttr);
}
