package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;

import java.io.InputStream;
import java.util.List;

public interface PriceLinkedItemService {
  List<PriceLinkedItemDto> list(String pricingMonth, String materialCode);

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
   * @param input        .xlsx 二进制流
   * @param pricingMonth 价期月（如 "2026-02"），必填
   */
  PriceItemImportResponse importExcel(InputStream input, String pricingMonth);

  boolean delete(Long id);
}
