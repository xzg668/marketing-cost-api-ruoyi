package com.sanhua.marketingcost.service;

/**
 * 按报价产品及核算月份同步主档（staging → 主表）。
 *
 * <p>逻辑等价于 Python {@code scripts/sync_material_master.py}：
 * <ol>
 *   <li>取本产品、本月份涉及的去重料号</li>
 *   <li>按组织 + 料号读取 staging 当前有效行</li>
 *   <li>从 staging 拉这些料号的行，做字段映射 + 类型转换 + BU 推断</li>
 *   <li>UPSERT 到 lp_material_master（ON DUPLICATE KEY UPDATE 刷字段）</li>
 * </ol>
 */
public interface MaterialMasterSyncService {
  /**
   * 同步本产品涉及料号到主档；单品入口和整单任务共用。
   *
   * @param oaNo OA 单号
   * @return 同步结果（涉及料号数 / staging 命中数 / UPSERT 受影响行数）
   * @throws RuntimeException 同步失败（DB 或系统错误）
   */
  SyncResult syncProduct(String oaNo, Long itemId, String periodMonth);

  /** 同步结果汇总。staging 命中 < 涉及料号说明部分料号 staging 缺数据，调用侧可记日志。 */
  record SyncResult(int distinctCodes, int stagingHits, int affectedRows, String batchId) {}
}
