package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PriceLinkedItemMapper extends BaseMapper<PriceLinkedItem> {

  /** 批量取价按事业部和料号查规则，避免导入后统计信息滞后时扫描全部原稿底稿。 */
  @DataScope
  @Select(
      """
      <script>
      SELECT * FROM lp_price_linked_item FORCE INDEX (idx_linked_import_version_lookup)
      ${ew.customSqlSegment}
      </script>
      """)
  List<PriceLinkedItem> selectPricingCandidates(@Param("ew") Wrapper<PriceLinkedItem> query);

  @Select(
      """
      SELECT * FROM lp_price_linked_item
      WHERE business_unit_type=#{key.businessUnitType} AND rule_identity_key=#{key.identityKey}
        AND pricing_month=#{pricingMonth} AND rule_status='PUBLISHED' AND deleted=0
        AND source_kind='PUBLIC'
      ORDER BY rule_revision DESC LIMIT 1
      """)
  PriceLinkedItem findPublishedVersion(
      @Param("key") MaterialPriceRuleKey key, @Param("pricingMonth") String pricingMonth);

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<PriceLinkedItem> selectList(@Param("ew") Wrapper<PriceLinkedItem> queryWrapper);

  /** V21：分页查询最终走 BaseMapper.selectPage -> selectList(page, wrapper)，这里加数据隔离。 */
  @DataScope
  @Override
  List<PriceLinkedItem> selectList(
      IPage<PriceLinkedItem> page, @Param(Constants.WRAPPER) Wrapper<PriceLinkedItem> queryWrapper);
}
