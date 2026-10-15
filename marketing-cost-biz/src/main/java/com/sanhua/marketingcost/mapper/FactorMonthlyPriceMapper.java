package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FactorMonthlyPriceMapper extends BaseMapper<FactorMonthlyPrice> {

  @Select(
      """
      SELECT COUNT(*) FROM lp_factor_monthly_price p
      JOIN lp_factor_identity f ON f.id=p.factor_identity_id
      JOIN lp_factor_upload_batch b ON b.id=p.source_upload_batch_id
      WHERE f.business_unit_type=#{businessUnit} AND f.canonical_factor_key=#{key}
        AND p.status='PUBLISHED' AND b.import_type='FINANCE_BASE' AND b.status='PUBLISHED'
      """)
  long countFinanceManagedKey(@Param("businessUnit") String businessUnit, @Param("key") String key);

  @Select(
      """
      SELECT COUNT(*) FROM lp_factor_monthly_price p
      JOIN lp_factor_identity f ON f.id=p.factor_identity_id
      JOIN lp_factor_upload_batch b ON b.id=p.source_upload_batch_id
      WHERE f.business_unit_type=#{businessUnit} AND f.canonical_factor_key=#{key}
        AND p.price_org_code=#{organization} AND p.status='PUBLISHED'
        AND b.import_type='FINANCE_BASE' AND b.status='PUBLISHED'
      """)
  long countPublishedFactorKey(
      @Param("businessUnit") String businessUnit,
      @Param("organization") String organization,
      @Param("key") String key);

  @Select(
      "SELECT *"
          + " FROM lp_factor_monthly_price"
          + " WHERE factor_identity_id = #{factorIdentityId}"
          + "   AND price_month = #{priceMonth}"
          + "   AND status = 'ACTIVE'"
          + "   AND scheme_code = 'LEGACY'"
          + " LIMIT 1")
  FactorMonthlyPrice findActiveByIdentityAndMonth(
      @Param("factorIdentityId") Long factorIdentityId, @Param("priceMonth") String priceMonth);

  /** 一次读取整个已发布方案，固定同一个发布批次；缺因素不能偷偷拼接上一修订。 revision 为 null 取该精确月份的最新发布；指定修订用于复现历史输入。 */
  @Select(
      """
      <script>
      SELECT p.* FROM lp_factor_monthly_price p
      JOIN lp_factor_identity f ON f.id=p.factor_identity_id
      WHERE p.source_upload_batch_id=(
        SELECT b.id FROM lp_factor_upload_batch b
        WHERE b.business_unit_type=#{scope.businessUnitType}
          AND b.price_org_code=#{scope.priceOrgCode} AND b.price_month=#{scope.priceMonth}
          AND b.import_type='FINANCE_BASE' AND b.status='PUBLISHED'
          AND b.published_at IS NOT NULL AND b.publication_revision IS NOT NULL
          <if test="revision != null">AND b.publication_revision=#{revision}</if>
        ORDER BY b.publication_revision DESC LIMIT 1
      )
        AND f.business_unit_type=#{scope.businessUnitType}
        AND p.price_org_code=#{scope.priceOrgCode} AND p.price_month=#{scope.priceMonth}
        AND p.scheme_code=#{scope.schemeCode} AND p.condition_code=#{scope.condition}
        AND p.status='PUBLISHED' AND p.published_at IS NOT NULL
      ORDER BY p.factor_identity_id
      </script>
      """)
  List<FactorMonthlyPrice> findPublishedScheme(
      @Param("scope") FactorPriceScope scope, @Param("revision") Integer revision);
}
