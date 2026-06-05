package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.ingest.QuoteRequestProductBomListItemResponse;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuoteRequestProductBomMapper {

  @Select(
      """
      <script>
      SELECT COUNT(1)
        FROM oa_form_item i
        JOIN oa_form f ON f.id = i.oa_form_id
        LEFT JOIN lp_quote_bom_status s ON s.oa_form_item_id = i.id
        LEFT JOIN lp_quote_bom_preparation_record r
          ON r.oa_form_item_id = i.id AND r.active_flag = 1
      <where>
        (i.deleted IS NULL OR i.deleted = 0)
        AND (f.deleted IS NULL OR f.deleted = 0)
        <if test="oaNo != null and oaNo != ''">
          AND f.oa_no LIKE CONCAT('%', #{oaNo}, '%')
        </if>
        <if test="productCode != null and productCode != ''">
          AND i.material_no LIKE CONCAT('%', #{productCode}, '%')
        </if>
        <if test="customer != null and customer != ''">
          AND (
            i.customer_code LIKE CONCAT('%', #{customer}, '%')
            OR f.customer LIKE CONCAT('%', #{customer}, '%')
          )
        </if>
        <if test="productType != null and productType != ''">
          AND COALESCE(r.product_type, s.product_type, 'UNKNOWN') = #{productType}
        </if>
        <if test="packageMethod != null and packageMethod != ''">
          AND i.package_method LIKE CONCAT('%', #{packageMethod}, '%')
        </if>
        <if test="businessUnit != null and businessUnit != ''">
          AND COALESCE(i.business_unit_type, f.business_unit_type) = #{businessUnit}
        </if>
        <if test="technicianName != null and technicianName != ''">
          AND COALESCE(r.technician_name, s.technician_name, i.technician_name) LIKE CONCAT('%', #{technicianName}, '%')
        </if>
        <if test="needTechnicianTask != null">
          AND (
            CASE
              WHEN r.task_id IS NOT NULL THEN 1
              WHEN r.preparation_status IN ('NEED_TECH', 'TECH_SUBMITTED') THEN 1
              WHEN COALESCE(s.bom_status, '') IN ('NO_BOM', 'ENTRY_PENDING', 'ENTRY_IN_PROGRESS') THEN 1
              ELSE 0
            END
          ) = #{needTechnicianTask}
        </if>
        <if test="reviewStatus != null and reviewStatus != ''">
          AND COALESCE(r.review_status, s.review_status, 'NOT_SUBMITTED') = #{reviewStatus}
        </if>
        <if test="bomStatuses != null and bomStatuses.size() > 0">
          AND COALESCE(
            s.bom_status,
            CASE WHEN i.material_no IS NULL OR CHAR_LENGTH(TRIM(i.material_no)) = 0 THEN 'NO_BOM' ELSE 'NOT_CHECKED' END
          ) IN
          <foreach collection="bomStatuses" item="status" open="(" separator="," close=")">
            #{status}
          </foreach>
        </if>
      </where>
      </script>
      """)
  long countProductBomRows(
      @Param("oaNo") String oaNo,
      @Param("productCode") String productCode,
      @Param("customer") String customer,
      @Param("productType") String productType,
      @Param("packageMethod") String packageMethod,
      @Param("businessUnit") String businessUnit,
      @Param("technicianName") String technicianName,
      @Param("needTechnicianTask") Boolean needTechnicianTask,
      @Param("reviewStatus") String reviewStatus,
      @Param("bomStatuses") List<String> bomStatuses);

  @Select(
      """
      <script>
      SELECT
        s.id AS quoteBomStatusId,
        i.id AS oaFormItemId,
        f.oa_no AS oaNo,
        COALESCE(s.product_code, i.material_no) AS productCode,
        i.product_name AS productName,
        i.spec AS productSpec,
        COALESCE(
          CASE WHEN s.customer_code IS NOT NULL AND CHAR_LENGTH(TRIM(s.customer_code)) > 0 THEN TRIM(s.customer_code) END,
          CASE WHEN i.customer_code IS NOT NULL AND CHAR_LENGTH(TRIM(i.customer_code)) > 0 THEN TRIM(i.customer_code) END,
          f.customer,
          ''
        ) AS customerCode,
        f.customer AS customerName,
        COALESCE(r.product_type, s.product_type, 'UNKNOWN') AS productType,
        COALESCE(r.bare_product_code, s.bare_product_code) AS bareProductCode,
        COALESCE(s.package_method, i.package_method, '') AS packageMethod,
        COALESCE(i.business_unit_type, f.business_unit_type) AS businessUnitType,
        COALESCE(r.technician_name, s.technician_name, i.technician_name) AS technicianName,
        r.preparation_status AS preparationStatus,
        CASE
          WHEN COALESCE(r.preparation_status, '') IN ('READY', 'CONFIRMED') THEN 1
          WHEN COALESCE(s.bom_status, '') IN ('SYNCED', 'REUSED_CURRENT_MONTH', 'MANUAL_ENTERED') THEN 1
          ELSE 0
        END AS bodyBomReady,
        COALESCE(r.need_package, s.need_package, 0) AS needPackage,
        CASE
          WHEN CHAR_LENGTH(TRIM(COALESCE(r.reference_finished_code, s.reference_finished_code, ''))) > 0 THEN 1
          ELSE 0
        END AS packageReferenceReady,
        CASE
          WHEN r.task_id IS NOT NULL THEN 1
          WHEN r.preparation_status IN ('NEED_TECH', 'TECH_SUBMITTED') THEN 1
          WHEN COALESCE(s.bom_status, '') IN ('NO_BOM', 'ENTRY_PENDING', 'ENTRY_IN_PROGRESS') THEN 1
          ELSE 0
        END AS needTechnicianTask,
        COALESCE(r.task_id, s.supplement_task_id) AS taskId,
        t.task_no AS taskNo,
        COALESCE(r.review_status, s.review_status, 'NOT_SUBMITTED') AS reviewStatus,
        COALESCE(
          s.bom_status,
          CASE WHEN i.material_no IS NULL OR CHAR_LENGTH(TRIM(i.material_no)) = 0 THEN 'NO_BOM' ELSE 'NOT_CHECKED' END
        ) AS bomStatus,
        COALESCE(s.sync_at, s.checked_at) AS syncAt,
        COALESCE(r.updated_at, t.updated_at, s.sync_at, s.checked_at) AS lastHandledAt,
        COALESCE(r.costing_build_batch_id, s.costing_build_batch_id) AS costingBuildBatchId,
        s.reused_from_record_id AS reusedFromRecordId,
        COALESCE(todo.oa_todo_id, todo.todo_no) AS oaTodoId,
        COALESCE(todo.oa_todo_url, todo.todo_url) AS oaTodoUrl,
        COALESCE(todo.push_status, todo.todo_status, 'NOT_PUSHED') AS oaTodoPushStatus,
        todo.push_error_message AS oaTodoPushErrorMessage,
        COALESCE(todo.last_push_at, todo.pushed_at) AS oaTodoLastPushAt,
        COALESCE(r.error_message, s.error_message) AS errorMessage
        FROM oa_form_item i
        JOIN oa_form f ON f.id = i.oa_form_id
        LEFT JOIN lp_quote_bom_status s ON s.oa_form_item_id = i.id
        LEFT JOIN lp_quote_bom_preparation_record r
          ON r.oa_form_item_id = i.id AND r.active_flag = 1
        LEFT JOIN lp_bom_supplement_task t
          ON t.id = COALESCE(r.task_id, s.supplement_task_id)
        LEFT JOIN lp_bom_supplement_todo todo
          ON todo.task_id = t.id
         AND todo.recipient_role = 'TECHNICIAN'
         AND todo.todo_kind = 'TODO'
      <where>
        (i.deleted IS NULL OR i.deleted = 0)
        AND (f.deleted IS NULL OR f.deleted = 0)
        <if test="oaNo != null and oaNo != ''">
          AND f.oa_no LIKE CONCAT('%', #{oaNo}, '%')
        </if>
        <if test="productCode != null and productCode != ''">
          AND i.material_no LIKE CONCAT('%', #{productCode}, '%')
        </if>
        <if test="customer != null and customer != ''">
          AND (
            i.customer_code LIKE CONCAT('%', #{customer}, '%')
            OR f.customer LIKE CONCAT('%', #{customer}, '%')
          )
        </if>
        <if test="productType != null and productType != ''">
          AND COALESCE(r.product_type, s.product_type, 'UNKNOWN') = #{productType}
        </if>
        <if test="packageMethod != null and packageMethod != ''">
          AND i.package_method LIKE CONCAT('%', #{packageMethod}, '%')
        </if>
        <if test="businessUnit != null and businessUnit != ''">
          AND COALESCE(i.business_unit_type, f.business_unit_type) = #{businessUnit}
        </if>
        <if test="technicianName != null and technicianName != ''">
          AND COALESCE(r.technician_name, s.technician_name, i.technician_name) LIKE CONCAT('%', #{technicianName}, '%')
        </if>
        <if test="needTechnicianTask != null">
          AND (
            CASE
              WHEN r.task_id IS NOT NULL THEN 1
              WHEN r.preparation_status IN ('NEED_TECH', 'TECH_SUBMITTED') THEN 1
              WHEN COALESCE(s.bom_status, '') IN ('NO_BOM', 'ENTRY_PENDING', 'ENTRY_IN_PROGRESS') THEN 1
              ELSE 0
            END
          ) = #{needTechnicianTask}
        </if>
        <if test="reviewStatus != null and reviewStatus != ''">
          AND COALESCE(r.review_status, s.review_status, 'NOT_SUBMITTED') = #{reviewStatus}
        </if>
        <if test="bomStatuses != null and bomStatuses.size() > 0">
          AND COALESCE(
            s.bom_status,
            CASE WHEN i.material_no IS NULL OR CHAR_LENGTH(TRIM(i.material_no)) = 0 THEN 'NO_BOM' ELSE 'NOT_CHECKED' END
          ) IN
          <foreach collection="bomStatuses" item="status" open="(" separator="," close=")">
            #{status}
          </foreach>
        </if>
      </where>
       ORDER BY f.apply_date DESC, f.id DESC, i.seq ASC, i.id ASC
       LIMIT #{pageSize} OFFSET #{offset}
      </script>
      """)
  List<QuoteRequestProductBomListItemResponse> selectProductBomRows(
      @Param("oaNo") String oaNo,
      @Param("productCode") String productCode,
      @Param("customer") String customer,
      @Param("productType") String productType,
      @Param("packageMethod") String packageMethod,
      @Param("businessUnit") String businessUnit,
      @Param("technicianName") String technicianName,
      @Param("needTechnicianTask") Boolean needTechnicianTask,
      @Param("reviewStatus") String reviewStatus,
      @Param("bomStatuses") List<String> bomStatuses,
      @Param("pageSize") int pageSize,
      @Param("offset") int offset);
}
