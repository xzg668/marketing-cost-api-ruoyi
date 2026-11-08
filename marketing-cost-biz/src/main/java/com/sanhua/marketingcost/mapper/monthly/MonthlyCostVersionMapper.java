package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import org.apache.ibatis.annotations.*;

/** Serializes final publication of one existing cost version; no new table or pricing route. */
@Mapper
public interface MonthlyCostVersionMapper {
  record Part(com.sanhua.marketingcost.entity.CostRunPartItem item,Long monthlyPriceId,String monthlyPricingType) {}

  /** A new customer's complete rows and their existing price references are inserted together. */
  @Insert("""
      <script>INSERT INTO lp_cost_run_part_item
        (oa_no,oa_form_item_id,cost_run_version_id,cost_run_no,product_code,business_unit_type,
         part_code,part_name,qty,unit_price,amount,price_prepare_item_id,bom_row_id,price_source,
         price_org_code,material_organization_code,monthly_price_id,monthly_pricing_type,created_at,updated_at)
      VALUES <foreach collection="rows" item="p" separator=",">
        (#{p.item.oaNo},#{p.item.oaFormItemId},#{p.item.costRunVersionId},#{p.item.costRunNo},
         #{p.item.productCode},#{p.item.businessUnitType},#{p.item.partCode},#{p.item.partName},
         #{p.item.qty},#{p.item.unitPrice},#{p.item.amount},#{p.item.pricePrepareItemId},#{p.item.bomRowId},
         #{p.item.priceSource},#{p.item.priceOrgCode},#{p.item.materialOrganizationCode},
         #{p.monthlyPriceId},#{p.monthlyPricingType},#{p.item.createdAt},#{p.item.updatedAt})
      </foreach></script>
      """)
  int insertParts(@Param("rows") java.util.List<Part> rows);

  @Insert("""
      <script>INSERT INTO lp_cost_run_cost_item
        (oa_no,oa_form_item_id,cost_run_version_id,cost_run_no,product_code,business_unit_type,
         line_no,category,cost_code,cost_name,base_amount,rate,amount,source_table,source_id,remark,created_at,updated_at)
      VALUES <foreach collection="rows" item="c" separator=",">
        (#{c.oaNo},#{c.oaFormItemId},#{c.costRunVersionId},#{c.costRunNo},#{c.productCode},#{c.businessUnitType},
         #{c.lineNo},#{c.category},#{c.costCode},#{c.costName},#{c.baseAmount},#{c.rate},#{c.amount},
         #{c.sourceTable},#{c.sourceId},#{c.remark},#{c.createdAt},#{c.updatedAt})
      </foreach></script>
      """)
  int insertCosts(@Param("rows") java.util.List<com.sanhua.marketingcost.entity.CostRunCostItem> rows);

  @Select("SELECT * FROM lp_quote_cost_run_version WHERE id=#{id} AND business_unit_type=#{unit} FOR UPDATE")
  QuoteCostRunVersion lock(@Param("unit") String unit, @Param("id") long id);

  record SavedSnapshot(long id, Integer version, String json) {}
  @Select("""
      <script>SELECT id,jianji_snapshot_version version,jianji_snapshot_json json FROM lp_quote_cost_run_version
      WHERE business_unit_type=#{unit} AND id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
      """)
  java.util.List<SavedSnapshot> snapshots(@Param("unit") String unit,@Param("ids") java.util.List<Long> ids);
}
