package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteCollaborationQuoteLinkMapper
    extends BaseMapper<QuoteCollaborationQuoteLink> {

  @Select("""
      SELECT l.* FROM lp_quote_collaboration_quote_link l
      JOIN lp_quote_collaboration_product_task p ON p.id = l.product_task_id
      WHERE l.oa_form_item_id = #{oaFormItemId} AND l.active_flag = 1
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      ORDER BY l.id DESC
      """)
  List<QuoteCollaborationQuoteLink> selectActiveByQuoteItem(
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT l.* FROM lp_quote_collaboration_quote_link l
      JOIN lp_quote_collaboration_product_task p ON p.id = l.product_task_id
      WHERE l.product_task_id = #{productTaskId}
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      ORDER BY l.id
      """)
  List<QuoteCollaborationQuoteLink> selectByProductTask(
      @Param("productTaskId") Long productTaskId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT l.* FROM lp_quote_collaboration_quote_link l
      JOIN lp_quote_collaboration_product_task p ON p.id = l.product_task_id
      WHERE l.id = #{id} AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      """)
  QuoteCollaborationQuoteLink selectScopedById(
      @Param("id") Long id,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Update("""
      UPDATE lp_quote_collaboration_quote_link l
      JOIN lp_quote_collaboration_product_task p ON p.id = l.product_task_id
      SET l.link_status = #{nextStatus},
          l.ready_at = CASE
            WHEN #{nextStatus} = 'READY' THEN COALESCE(l.ready_at, NOW())
            WHEN #{nextStatus} = 'RECHECKING' THEN NULL
            ELSE l.ready_at
          END,
          l.active_flag = CASE WHEN #{nextStatus} = 'CANCELLED' THEN 0 ELSE l.active_flag END,
          l.active_link_key = CASE
            WHEN #{nextStatus} = 'CANCELLED' THEN NULL ELSE l.active_link_key
          END,
          l.updated_by = #{updatedBy}, l.updated_by_name = #{updatedByName},
          l.updated_at = NOW()
      WHERE l.id = #{id} AND l.link_status = #{expectedStatus}
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      """)
  int transitionStatus(
      @Param("id") Long id,
      @Param("expectedStatus") String expectedStatus,
      @Param("nextStatus") String nextStatus,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);
}
