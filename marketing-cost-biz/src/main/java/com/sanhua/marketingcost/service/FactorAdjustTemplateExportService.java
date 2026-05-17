package com.sanhua.marketingcost.service;

public interface FactorAdjustTemplateExportService {

  /**
   * 导出月度调价模板。
   *
   * <p>未传 adjustBatchId 时导出目标月份日常报价生效价；传入 adjustBatchId 时导出该调价批次价。
   * 导出的 Excel 包含隐藏系统识别列，可被 FactorAdjustExcelParseService 精准匹配回来。
   */
  byte[] exportTemplate(
      String pricingMonth, String businessUnitType, String keyword, Long adjustBatchId);
}
