package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.dto.BomBatchSummary;
import com.sanhua.marketingcost.entity.BomU9Source;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * lp_bom_u9_source 访问层。
 *
 * <p>T2 阶段只继承 BaseMapper；T3 补 {@link #listBatchSummaries} 供 {@code GET /batches?layer=U9_SOURCE} 使用。
 */
@Mapper
public interface BomU9SourceMapper extends BaseMapper<BomU9Source> {

  /**
   * 按 import_batch_id 聚合查询导入批次摘要，按时间倒序分页。
   *
   * <p>为什么不走 BaseMapper 的 selectPage：MP 的 selectPage 基于实体一行一记录，这里要的是
   * 聚合后的摘要行（批次维度），直接写 @Select 最简单。
   *
   * @param offset 跳过多少条（(page-1)*size）
   * @param limit 取多少条
   */
  @Select(
      "SELECT import_batch_id AS batchId,"
          + "       MAX(source_type) AS sourceType,"
          + "       MAX(source_file_name) AS sourceFileName,"
          + "       COUNT(*) AS rowCount,"
          + "       MAX(imported_at) AS importedAt,"
          + "       MAX(imported_by) AS importedBy"
          + "  FROM lp_bom_u9_source"
          + " GROUP BY import_batch_id"
          + " ORDER BY MAX(imported_at) DESC"
          + " LIMIT #{offset}, #{limit}")
  List<BomBatchSummary> listBatchSummaries(@Param("offset") int offset, @Param("limit") int limit);

  /**
   * 查询某批次里出现过的所有 bom_purpose。
   *
   * <p>用于 "import-and-build" 合成端点：导入后按 purpose 循环触发 build ALL，
   * 避免财务需要知道系统里有哪些 purpose 然后逐个点。
   *
   * @param importBatchId 本次导入批次
   * @return purpose 列表（过滤空值，去重）
   */
  @Select(
      "SELECT DISTINCT bom_purpose"
          + "  FROM lp_bom_u9_source"
          + " WHERE import_batch_id = #{importBatchId}"
          + "   AND bom_purpose IS NOT NULL"
          + "   AND bom_purpose <> ''")
  List<String> findDistinctPurposes(@Param("importBatchId") String importBatchId);
}
