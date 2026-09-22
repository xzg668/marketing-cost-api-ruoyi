package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteTechProductMapper extends BaseMapper<QuoteTechProduct> {

  @Insert("""
      INSERT INTO lp_quote_tech_product (
        task_id,oa_form_item_id,level_no,material_no,product_name,source_model,source_spec,
        quote_no,accounting_month,source_snapshot_json,source_fingerprint,product_status,
        active_flag,active_lock_key,row_version,content_schema_version)
      VALUES (
        #{product.taskId},#{product.oaFormItemId},#{product.levelNo},#{product.materialNo},
        #{product.productName},#{product.sourceModel},#{product.sourceSpec},#{product.quoteNo},
        #{product.accountingMonth},#{product.sourceSnapshotJson},#{product.sourceFingerprint},
        #{product.productStatus},#{product.activeFlag},#{product.activeLockKey},
        #{product.rowVersion},#{product.contentSchemaVersion})
      ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "product.id")
  int insertOrGetActive(@Param("product") QuoteTechProduct product);

  @Select("""
      SELECT *
        FROM lp_quote_tech_product
       WHERE oa_form_item_id = #{oaFormItemId}
         AND accounting_month = #{accountingMonth}
         AND active_flag = 1
       LIMIT 1
      """)
  QuoteTechProduct selectActiveByItemAndMonth(
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("accountingMonth") String accountingMonth);

  @Select("""
      SELECT *
        FROM lp_quote_tech_product
       WHERE oa_form_item_id = #{oaFormItemId}
         AND accounting_month = #{accountingMonth}
         AND active_flag = 1
       ORDER BY id
      """)
  List<QuoteTechProduct> selectActiveCandidatesByItemAndMonth(
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("accountingMonth") String accountingMonth);

  @Select("SELECT * FROM lp_quote_tech_product WHERE id=#{productId} FOR UPDATE")
  QuoteTechProduct selectByIdForUpdate(@Param("productId") Long productId);

  @Select("""
      SELECT * FROM lp_quote_tech_product
       WHERE task_id=#{taskId}
       ORDER BY level_no,oa_form_item_id,id
      """)
  List<QuoteTechProduct> selectByTaskId(@Param("taskId") Long taskId);

  @Select("""
      SELECT * FROM lp_quote_tech_product
       WHERE task_id=#{taskId} AND active_flag=1
       ORDER BY level_no,oa_form_item_id,id
       FOR UPDATE
      """)
  List<QuoteTechProduct> selectActiveByTaskIdForUpdate(@Param("taskId") Long taskId);

  @Select({
      "<script>",
      "SELECT COUNT(*)",
      "FROM lp_quote_tech_product product",
      "JOIN lp_quote_tech_task task ON task.id=product.task_id",
      "WHERE product.active_flag=1 AND task.active_flag=1",
      "<if test='taskStatus != null and taskStatus != \"\"'>",
      "AND task.task_status=#{taskStatus}",
      "</if>",
      "<if test='accountingMonth != null and accountingMonth != \"\"'>",
      "AND task.accounting_month=#{accountingMonth}",
      "</if>",
      "<if test='keyword != null and keyword != \"\"'>",
      "AND (task.task_no LIKE CONCAT('%',#{keyword},'%')",
      "OR task.oa_no LIKE CONCAT('%',#{keyword},'%')",
      "OR product.quote_no LIKE CONCAT('%',#{keyword},'%')",
      "OR product.material_no LIKE CONCAT('%',#{keyword},'%')",
      "OR product.product_name LIKE CONCAT('%',#{keyword},'%')",
      "OR product.source_model LIKE CONCAT('%',#{keyword},'%'))",
      "</if>",
      "<choose>",
      "<when test='accessMode == \"ALL\"'></when>",
      "<when test='accessMode == \"FINANCE\"'>AND task.business_unit_type=#{businessUnitType}</when>",
      "<otherwise>AND ((task.assignee_user_id=#{userId} AND NOT EXISTS (SELECT 1 FROM lp_quote_tech_module fm JOIN lp_quote_tech_product fp ON fp.id=fm.product_id WHERE fp.task_id=task.id AND fp.active_flag=1 AND fm.required_flag=1 AND fm.assignee_user_id IS NOT NULL)) OR EXISTS (SELECT 1 FROM lp_quote_tech_module am JOIN lp_quote_tech_product ap ON ap.id=am.product_id WHERE ap.task_id=task.id AND ap.active_flag=1 AND am.required_flag=1 AND am.assignee_user_id=#{userId}))</otherwise>",
      "</choose>",
      "</script>"
  })
  long countAccessibleWorkbenchRows(
      @Param("accessMode") String accessMode,
      @Param("userId") Long userId,
      @Param("businessUnitType") String businessUnitType,
      @Param("taskStatus") String taskStatus,
      @Param("accountingMonth") String accountingMonth,
      @Param("keyword") String keyword);

  @Select({
      "<script>",
      "SELECT product.*",
      "FROM lp_quote_tech_product product",
      "JOIN lp_quote_tech_task task ON task.id=product.task_id",
      "WHERE product.active_flag=1 AND task.active_flag=1",
      "<if test='taskStatus != null and taskStatus != \"\"'>",
      "AND task.task_status=#{taskStatus}",
      "</if>",
      "<if test='accountingMonth != null and accountingMonth != \"\"'>",
      "AND task.accounting_month=#{accountingMonth}",
      "</if>",
      "<if test='keyword != null and keyword != \"\"'>",
      "AND (task.task_no LIKE CONCAT('%',#{keyword},'%')",
      "OR task.oa_no LIKE CONCAT('%',#{keyword},'%')",
      "OR product.quote_no LIKE CONCAT('%',#{keyword},'%')",
      "OR product.material_no LIKE CONCAT('%',#{keyword},'%')",
      "OR product.product_name LIKE CONCAT('%',#{keyword},'%')",
      "OR product.source_model LIKE CONCAT('%',#{keyword},'%'))",
      "</if>",
      "<choose>",
      "<when test='accessMode == \"ALL\"'></when>",
      "<when test='accessMode == \"FINANCE\"'>AND task.business_unit_type=#{businessUnitType}</when>",
      "<otherwise>AND ((task.assignee_user_id=#{userId} AND NOT EXISTS (SELECT 1 FROM lp_quote_tech_module fm JOIN lp_quote_tech_product fp ON fp.id=fm.product_id WHERE fp.task_id=task.id AND fp.active_flag=1 AND fm.required_flag=1 AND fm.assignee_user_id IS NOT NULL)) OR EXISTS (SELECT 1 FROM lp_quote_tech_module am JOIN lp_quote_tech_product ap ON ap.id=am.product_id WHERE ap.task_id=task.id AND ap.active_flag=1 AND am.required_flag=1 AND am.assignee_user_id=#{userId}))</otherwise>",
      "</choose>",
      "ORDER BY task.updated_at DESC,task.id DESC,product.level_no,product.oa_form_item_id,product.id",
      "LIMIT #{offset},#{size}",
      "</script>"
  })
  List<QuoteTechProduct> selectAccessibleWorkbenchPage(
      @Param("accessMode") String accessMode,
      @Param("userId") Long userId,
      @Param("businessUnitType") String businessUnitType,
      @Param("taskStatus") String taskStatus,
      @Param("accountingMonth") String accountingMonth,
      @Param("keyword") String keyword,
      @Param("offset") int offset,
      @Param("size") int size);

  @Update("""
      UPDATE lp_quote_tech_product
         SET active_flag=0,active_lock_key=NULL,row_version=row_version+1,
             updated_at=#{changedAt}
       WHERE task_id=#{taskId} AND active_flag=1
      """)
  int deactivateByTaskId(
      @Param("taskId") Long taskId, @Param("changedAt") LocalDateTime changedAt);

  @Update("""
      UPDATE lp_quote_tech_product
         SET product_status = #{product.productStatus},
             current_edit_version_id = #{product.currentEditVersionId},
             latest_submitted_version_id = #{product.latestSubmittedVersionId},
             effective_version_id = #{product.effectiveVersionId},
             effective_review_round = #{product.effectiveReviewRound},
             effective_at = #{product.effectiveAt},
             row_version = row_version + 1,
             updated_at = #{updatedAt}
       WHERE id = #{product.id}
         AND row_version = #{expectedVersion}
         AND active_flag = 1
      """)
  int updatePointersWithVersion(
      @Param("product") QuoteTechProduct product,
      @Param("expectedVersion") int expectedVersion,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_quote_tech_product
         SET current_edit_version_id=#{draftVersionId},
             product_status='EDITING',
             row_version=row_version+1,
             updated_at=#{updatedAt}
       WHERE id=#{productId}
         AND row_version=#{expectedVersion}
         AND active_flag=1
      """)
  int updateProfileDraftWithVersion(
      @Param("productId") Long productId,
      @Param("draftVersionId") Long draftVersionId,
      @Param("expectedVersion") int expectedVersion,
      @Param("updatedAt") LocalDateTime updatedAt);
}
