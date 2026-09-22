package com.sanhua.marketingcost.controller;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalPriceImportContext;
import com.sanhua.marketingcost.service.technicaldata.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{itemId}/price-prepare")
public class QuoteTechnicalPriceCorrectionController {
  private final TechnicalPriceCorrectionService service;
  private final TechnicalPriceCorrectionWorkbook workbook;
  private final TechnicalDataActorProvider actors;

  public QuoteTechnicalPriceCorrectionController(
      TechnicalPriceCorrectionService service,
      TechnicalPriceCorrectionWorkbook workbook,
      TechnicalDataActorProvider actors) {
    this.service = service;
    this.workbook = workbook;
    this.actors = actors;
  }

  @GetMapping("/technical-formulas/export")
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public void export(
      @PathVariable String oaNo,
      @PathVariable Long itemId,
      @RequestParam String periodMonth,
      @RequestParam Long technicalVersionId,
      HttpServletResponse response)
      throws IOException {
    var view = service.get(oaNo, itemId, periodMonth);
    if (view == null || view.items().isEmpty())
      throw new IllegalArgumentException("本产品当前没有待修正的自行公式");
    var scope =
        service.require(
            new TechnicalPriceImportContext(oaNo, itemId, technicalVersionId),
            periodMonth,
            view.businessUnitType(),
            actors.current(),
            false);
    byte[] bytes = workbook.export(scope, view);
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader(
        "Content-Disposition", "attachment; filename=technical-price-correction.xlsx");
    response.getOutputStream().write(bytes);
  }
}
