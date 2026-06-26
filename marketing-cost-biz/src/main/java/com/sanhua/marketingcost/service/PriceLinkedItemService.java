package com.sanhua.marketingcost.service;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.FactorUploadBatchDto;
import com.sanhua.marketingcost.dto.PriceLinkedImportBatchDetailDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;

import java.io.InputStream;
import java.util.List;

public interface PriceLinkedItemService {
  List<PriceLinkedItemDto> list(String pricingMonth, String materialCode);

  default List<PriceLinkedItemDto> list(
      String pricingMonth, String materialCode, boolean includeHistory) {
    return list(pricingMonth, materialCode);
  }

  default PageResult<PriceLinkedItemDto> page(
      String pricingMonth,
      String materialCode,
      boolean includeHistory,
      int page,
      int pageSize) {
    List<PriceLinkedItemDto> rows = list(pricingMonth, materialCode, includeHistory);
    return new PageResult<>(rows, (long) rows.size());
  }

  PriceLinkedItemDto create(PriceLinkedItemUpdateRequest request);

  PriceLinkedItemDto update(Long id, PriceLinkedItemUpdateRequest request);

  List<PriceLinkedItemDto> importItems(PriceLinkedItemImportRequest request);

  /**
   * 联动/固定价格 Excel 导入 —— T18 新增。
   *
   * <p>按 {@code orderType} 列分流：
   * <ul>
   *   <li>{@code "固定"} → {@code lp_price_fixed_item}</li>
   *   <li>{@code "联动"} 或空 → {@code lp_price_linked_item}</li>
   * </ul>
   * 联动行的公式会先过 {@code FormulaNormalizer}，非法公式记入 errors 不入库。
   *
   * @param input           .xlsx 二进制流
   * @param pricingMonth    价期月（如 "2026-02"），必填
   * @param overwriteManual 是否允许覆盖 MANUAL 行级绑定
   */
  PriceItemImportResponse importExcel(
      InputStream input, String pricingMonth, boolean overwriteManual);

  default PriceItemImportResponse importExcel(
      InputStream input,
      String pricingMonth,
      boolean overwriteManual,
      String businessUnitType,
      String sourceFileName) {
    return importExcel(input, pricingMonth, overwriteManual);
  }

  default PriceItemImportResponse importExcel(
      InputStream input,
      String pricingMonth,
      boolean overwriteManual,
      String businessUnitType,
      String sourceFileName,
      String effectiveStrategy) {
    return importExcel(input, pricingMonth, overwriteManual, businessUnitType, sourceFileName);
  }

  default PriceItemImportResponse importExcel(
      InputStream input,
      String pricingMonth,
      boolean overwriteManual,
      String businessUnitType,
      String sourceFileName,
      String effectiveStrategy,
      String formulaEffectiveDate,
      String factorPriceConflictStrategy) {
    return importExcel(
        input, pricingMonth, overwriteManual, businessUnitType, sourceFileName, effectiveStrategy);
  }

  default List<FactorUploadBatchDto> listImportHistory(
      String pricingMonth, String businessUnitType, Integer limit) {
    return List.of();
  }

  default List<FactorUploadBatchDto> listImportHistory(
      String pricingMonth,
      String businessUnitType,
      String uploadedBy,
      Boolean includeAllUploaders,
      Integer limit) {
    return listImportHistory(pricingMonth, businessUnitType, limit);
  }

  default PriceLinkedImportBatchDetailDto getImportBatchDetail(Long factorUploadBatchId) {
    return null;
  }

  boolean delete(Long id);
}
