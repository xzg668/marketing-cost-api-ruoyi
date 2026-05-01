package com.sanhua.marketingcost.service;

/**
 * T15：按 OA 同步主档（staging → 主表）。
 *
 * <p>逻辑等价于 Python {@code scripts/sync_material_master.py}：
 * <ol>
 *   <li>取 OA 涉及的去重料号（lp_bom_costing_row）</li>
 *   <li>拿 staging 最新批次（lp_material_master_raw.import_batch_id）</li>
 *   <li>从 staging 拉这些料号的行，做字段映射 + 类型转换 + BU 推断</li>
 *   <li>UPSERT 到 lp_material_master（ON DUPLICATE KEY UPDATE 刷字段）</li>
 * </ol>
 */
public interface MaterialMasterSyncService {
  /**
   * 同步该 OA 涉及料号到主档。
   *
   * @param oaNo OA 单号
   * @return 同步结果（涉及料号数 / staging 命中数 / UPSERT 受影响行数）
   * @throws RuntimeException 同步失败（OA 无 BOM 行 / staging 无数据 / DB 错）
   */
  SyncResult syncByOaNo(String oaNo);

  /** 同步结果汇总。staging 命中 < 涉及料号说明部分料号 staging 缺数据，调用侧可记日志。 */
  record SyncResult(int distinctCodes, int stagingHits, int affectedRows, String batchId) {}
}
