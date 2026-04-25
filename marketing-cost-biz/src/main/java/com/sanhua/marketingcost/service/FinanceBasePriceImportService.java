package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.InfluenceFactorImportResponse;
import com.sanhua.marketingcost.dto.InfluenceFactorImportRow;
import java.io.InputStream;
import java.util.List;

/**
 * 影响因素 Excel 导入服务 —— T17 新增。
 *
 * <p>与老 {@link FinanceBasePriceService#importPrices} 的区别：
 * <ul>
 *   <li>入口是二进制 Excel 流（EasyExcel 按表头名解析），而非前端 POST 的 JSON</li>
 *   <li>每次导入生成 UUID 批次，所有入库行共享 {@code import_batch_id} 便于回滚/审计</li>
 *   <li>upsert 结束后调 {@code VariableAliasIndex.refresh()} 让公式编辑器立刻识别新别名</li>
 * </ul>
 */
public interface FinanceBasePriceImportService {

  /**
   * 从 Excel 流导入影响因素价格。
   *
   * @param input       Excel 二进制流（.xlsx）；由 Controller 从 MultipartFile 取
   * @param priceMonth  价期月（如 "2026-02"），必填 —— Excel 自身不承载价期列
   * @return 批次 ID + 统计 + 错误明细
   */
  InfluenceFactorImportResponse importExcel(InputStream input, String priceMonth);

  /**
   * 直接按已解析的行列表 upsert —— 内部复用，同时也供测试跳过 EasyExcel 解析阶段用。
   * 参数 {@code priceMonth} 必填；{@link InfluenceFactorImportRow} 会就地校验。
   */
  InfluenceFactorImportResponse importRows(List<InfluenceFactorImportRow> rows, String priceMonth);
}
