package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.BomBatchSummary;
import com.sanhua.marketingcost.dto.BomImportResult;
import com.sanhua.marketingcost.dto.ImportAndBuildResult;
import java.io.InputStream;
import java.util.List;

/**
 * BOM 阶段 A：导入 U9/Excel 原始数据到 lp_bom_u9_source。
 *
 * <p>职责边界：
 * <ul>
 *   <li>仅做字段映射 + 批量写库，不算 path / 不打标 / 不过滤（这些在 T4/T5）</li>
 *   <li>原子性：一次上传 = 一个 import_batch_id；批内任何 DB 异常全部回滚</li>
 *   <li>可追溯：记录文件名 / 导入人 / 导入时间</li>
 * </ul>
 */
public interface BomImportService {

  /**
   * 从 Excel 流导入 BOM 行到 {@code lp_bom_u9_source}。
   *
   * @param input Excel 输入流（由 Controller 从 MultipartFile 打开）
   * @param sourceFileName Excel 原文件名，入库用于追溯
   * @param importedBy 登录用户名
   * @return 导入结果：批次 ID + 统计 + 错误行明细
   */
  BomImportResult importExcel(InputStream input, String sourceFileName, String importedBy);

  /**
   * 查询导入批次摘要列表。
   *
   * @param layer {@code U9_SOURCE} / {@code RAW_HIERARCHY} / {@code COSTING_ROW}，T3 只实现第 1 个
   * @param page 页码，从 1 起
   * @param size 每页条数
   */
  List<BomBatchSummary> listBatches(String layer, int page, int size);

  /**
   * 合成操作：导入 Excel 后自动按每个 bom_purpose 跑一次 build ALL。
   *
   * <p>面向财务用户：一次点击解决"导入 + 展开层级"两步，不需要理解
   * raw_hierarchy / bomPurpose 等技术细节。底层仍复用 {@link #importExcel} 和
   * {@link com.sanhua.marketingcost.service.BomHierarchyBuildService#build}，
   * 任何一步失败在响应里按 status 标识出来。
   *
   * @return 导入 + 各 purpose 构建的合并结果
   */
  ImportAndBuildResult importAndBuild(InputStream input, String sourceFileName, String importedBy);
}
