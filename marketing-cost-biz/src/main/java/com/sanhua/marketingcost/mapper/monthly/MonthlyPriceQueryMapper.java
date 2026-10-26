package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.monthly.MonthlyMaterialPriceRow;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceQuery.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/**
 * No source-table joins or price adoption calls. Every query is bounded by the authenticated unit.
 */
@Mapper
public interface MonthlyPriceQueryMapper {
  String FILTER =
      """
FROM lp_material_monthly_price FORCE INDEX (idx_monthly_price_query)
WHERE business_unit_type=#{bu} AND pricing_month=#{q.month}
<if test="q.materialCode != null">AND material_code=#{q.materialCode} AND BINARY material_code=BINARY #{q.materialCode}</if>
<if test="q.organization != null">AND (organization_mask &amp; #{q.organizationMask}) != 0</if>
<if test="q.pricingType != null">AND pricing_type=#{q.pricingType}</if>
<if test="q.specification != null">AND BINARY specification=BINARY #{q.specification}</if>
<if test="q.unit != null">AND BINARY pricing_unit=BINARY #{q.unit}</if>
<if test="q.currency != null">AND currency=#{q.currency}</if>
<if test="q.taxBasis != null">AND tax_basis=#{q.taxBasis}</if>
<if test="q.supplier != null">AND BINARY requested_supplier_code=BINARY #{q.supplier}</if>
""";

  @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
  long count(@Param("bu") String bu, @Param("q") Filter filter);

  @Select(
      "<script>SELECT * "
          + FILTER
          + " ORDER BY material_code,id LIMIT #{q.size} OFFSET #{q.offset}</script>")
  List<MonthlyMaterialPriceRow> list(@Param("bu") String bu, @Param("q") Filter filter);

  @Select("SELECT * FROM lp_material_monthly_price WHERE id=#{id} AND business_unit_type=#{bu}")
  MonthlyMaterialPriceRow detail(@Param("bu") String bu, @Param("id") long id);

  // The saved calculation includes dependencies and material explanations, which a direct part FK
  // alone omits.
  String ADOPTED =
      "JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(v.jianji_snapshot_json,'$.document')),'$.calculation.adoptedPrices')";
  String REFERENCES =
      """
FROM lp_quote_cost_run_version v FORCE INDEX (idx_quote_cost_monthly_lookup)
JOIN oa_form_item i ON i.id=v.oa_form_item_id AND BINARY i.business_unit_type=BINARY v.business_unit_type
JOIN oa_form f ON f.id=i.oa_form_id AND BINARY f.business_unit_type=BINARY v.business_unit_type AND BINARY f.oa_no=BINARY v.oa_no
WHERE v.business_unit_type=#{bu} AND v.pricing_month=#{month}
  AND v.status IN ('SUCCESS','CONFIRMED','HISTORY','VOIDED','STALE')
  AND v.jianji_snapshot_version=1
  AND JSON_CONTAINS(
"""
          + "JSON_EXTRACT("
          + ADOPTED
          + ",'$[*].selection.monthlyPriceId'),CAST(#{id} AS JSON))";

  @Select("SELECT COUNT(*) " + REFERENCES)
  long referenceCount(@Param("bu") String bu, @Param("month") String month, @Param("id") long id);

  @Select(
      """
SELECT v.id version_id,v.oa_no,v.oa_form_item_id,v.product_code,v.version_no,v.cost_run_no,
  v.status,v.pricing_month,CAST(v.total_cost AS CHAR) total_cost,CAST(v.final_quote_amount AS CHAR) final_quote_amount,
  (SELECT GROUP_CONCAT(DISTINCT a.organization ORDER BY a.organization SEPARATOR ', ')
"""
          + "FROM JSON_TABLE("
          + ADOPTED
          + ", '$[*]' COLUMNS(price BIGINT PATH '$.selection.monthlyPriceId', organization"
          + " VARCHAR(3) PATH '$.request.priceOrgCode')) a WHERE a.price=#{id})"
          + " consumer_organizations "
          + REFERENCES
          + " ORDER BY v.id DESC LIMIT #{size} OFFSET #{offset}")
  List<Reference> references(
      @Param("bu") String bu,
      @Param("month") String month,
      @Param("id") long id,
      @Param("size") int size,
      @Param("offset") long offset);
}
