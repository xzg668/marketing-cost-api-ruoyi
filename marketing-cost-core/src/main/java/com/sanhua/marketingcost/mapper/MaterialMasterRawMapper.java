package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.service.MaterialMasterSyncService.BatchSummary;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** T15：U9 staging 表 mapper。同步用，找最新有效批次 + 按料号过滤。 */
@Mapper
public interface MaterialMasterRawMapper extends BaseMapper<MaterialMasterRaw> {

  /** 查 staging 最新有效批次 id（按字典序倒序），无数据返 null。 */
  @Select({
      "<script>",
      "SELECT MAX(import_batch_id)",
      "FROM lp_material_master_raw",
      "WHERE active_flag = 1",
      "<if test='sourceType != null and sourceType != \"\"'>",
      "  AND source_type = #{sourceType}",
      "</if>",
      "</script>"
  })
  String selectLatestActiveBatchId(@Param("sourceType") String sourceType);

  /** 兼容旧调用；语义已升级为最新有效批次。 */
  @Select("SELECT MAX(import_batch_id) FROM lp_material_master_raw WHERE active_flag = 1")
  String selectLatestBatchId();

  @Select({
      "SELECT",
      "  import_batch_id AS batchNo,",
      "  'MATERIAL_MASTER' AS datasetCode,",
      "  COALESCE(source_type, 'EXCEL') AS sourceType,",
      "  COALESCE(mapping_version, 'U9_ITEM_MASTER_20260519') AS mappingVersion,",
      "  COUNT(*) AS totalCount,",
      "  COUNT(*) AS successCount,",
      "  0 AS failCount,",
      "  CASE WHEN MAX(active_flag) = 1 THEN 'PARSED' ELSE 'ARCHIVED' END AS status",
      "FROM lp_material_master_raw",
      "GROUP BY import_batch_id, source_type, mapping_version",
      "ORDER BY import_batch_id DESC"
  })
  List<BatchSummary> listBatchSummaries();

  /**
   * 按最新有效批次读取指定料号。
   *
   * <p>raw 表保留历史批次；业务同步只能读取一个有效批次，避免同一料号混用新旧 U9 数据。
   */
  @Select({
      "<script>",
      "SELECT *",
      "FROM lp_material_master_raw",
      "WHERE active_flag = 1",
      "  AND import_batch_id = (",
      "    SELECT MAX(import_batch_id)",
      "    FROM lp_material_master_raw",
      "    WHERE active_flag = 1",
      "    <if test='sourceType != null and sourceType != \"\"'>",
      "      AND source_type = #{sourceType}",
      "    </if>",
      "  )",
      "<if test='sourceType != null and sourceType != \"\"'>",
      "  AND source_type = #{sourceType}",
      "</if>",
      "  AND material_code IN",
      "  <foreach collection='codes' item='code' open='(' separator=',' close=')'>",
      "    #{code}",
      "  </foreach>",
      "</script>"
  })
  List<MaterialMasterRaw> selectByLatestBatchAndCodes(
      @Param("codes") Collection<String> codes, @Param("sourceType") String sourceType);

  /**
   * 按最新有效批次读取包装组件父件候选。
   *
   * <p>包装组件父件常是 U9 虚拟件，不一定同步到主表；必须查 raw，但要限定批次。
   */
  @Select({
      "<script>",
      "SELECT *",
      "FROM lp_material_master_raw",
      "WHERE active_flag = 1",
      "  AND import_batch_id = (",
      "    SELECT MAX(import_batch_id)",
      "    FROM lp_material_master_raw",
      "    WHERE active_flag = 1",
      "    <if test='sourceType != null and sourceType != \"\"'>",
      "      AND source_type = #{sourceType}",
      "    </if>",
      "  )",
      "<if test='sourceType != null and sourceType != \"\"'>",
      "  AND source_type = #{sourceType}",
      "</if>",
      "  AND main_category_name = #{mainCategoryName}",
      "</script>"
  })
  List<MaterialMasterRaw> selectPackageComponentParentsByLatestBatch(
      @Param("mainCategoryName") String mainCategoryName, @Param("sourceType") String sourceType);
}
