package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 财务补齐表沿用现有导入格式；保留整组当前关系，避免回导时移出未展示的供应商。 */
@Service
public class SupplierPriceReviewExportService {
  private final QuotePricePrepareWorkbenchService prices;
  private final SupplierSupplyRatioMapper ratios;

  public SupplierPriceReviewExportService(QuotePricePrepareWorkbenchService prices,
      SupplierSupplyRatioMapper ratios) {
    this.prices = prices;
    this.ratios = ratios;
  }

  private record Relation(String supplierCode, String supplierName, BigDecimal ratio, String selectionRule) {}

  @Transactional(readOnly = true)
  public byte[] export(String oaNo, Long itemId, String periodMonth) throws IOException {
    var request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(periodMonth);
    request.setRefreshCandidates(true);
    // 与页面相同的只读检查，校验报价归属、业务单元和月份；不确认审核或采用月价。
    var saved = prices.getPricePrepare(oaNo, itemId, periodMonth);
    var response = prices.checkPriceSources(oaNo, itemId, request);
    Map<String, SupplierPriceReview> byScope = new LinkedHashMap<>();
    for (var review : response.getSupplierPriceReviews()) byScope.put(review.scopeKey(), review);
    // 最高价已经形成月价时，新的检查直接复用月价，不再产生审核事项；仍可导出页面保存的最高价依据。
    for (var review : saved.getSupplierPriceReviews()) {
      if (SupplierPriceSelectionService.FALLBACK_HIGH.equals(review.status())) byScope.putIfAbsent(review.scopeKey(), review);
    }
    var reviews = new ArrayList<>(byScope.values());
    if (reviews.isEmpty()) throw new IllegalArgumentException("当前没有需要补齐的供货比率，请刷新价格检查");
    String unit = reviews.getFirst().scope().businessUnitType();
    Map<String, SupplierPriceReviewMaterials.Material> details = new LinkedHashMap<>();
    for (var material : saved.getSupplierPriceReviewMaterials()) details.put(material.scopeKey(), material);
    for (var material : response.getSupplierPriceReviewMaterials()) details.put(material.scopeKey(), material);
    Map<String, SupplierPriceReviewMaterials.Material> materials = new LinkedHashMap<>();
    Map<String, String> codes = new LinkedHashMap<>();
    Map<String, LinkedHashSet<String>> messages = new LinkedHashMap<>();
    Map<String, Map<String, Relation>> groups = new LinkedHashMap<>();
    for (var review : reviews) {
      if (!Objects.equals(unit, review.scope().businessUnitType())) throw new IllegalArgumentException("不能混合导出不同业务单元");
      String code = key(review.scope().materialCode());
      var material = details.get(review.scopeKey());
      if (material == null) throw new IllegalArgumentException("物料档案未加载，请刷新后导出");
      var previous = materials.putIfAbsent(code, material);
      if (previous != null && !sameMaterial(previous, material)) {
        throw new IllegalArgumentException("同一料号在多个取价组织的档案不一致，请核对：" + review.scope().materialCode());
      }
      codes.putIfAbsent(code, review.scope().materialCode());
      groups.computeIfAbsent(code, ignored -> new LinkedHashMap<>());
      var reasons = messages.computeIfAbsent(code, ignored -> new LinkedHashSet<>());
      reasons.add(SupplierPriceSelectionService.FALLBACK_HIGH.equals(review.status())
          ? "已确认按最高价取价" : review.message());
    }
    // 导入按料号整组替换，因此还需带上当前关系中不在本次价格候选内的供应商，包括0和空白。
    for (var current : ratios.currentRelations(unit, null, new ArrayList<>(codes.values()))) {
      String code = key(current.getRequestedMaterialCode());
      var group = groups.get(code);
      if (group == null) throw new IllegalArgumentException("供货关系查询范围不一致，请刷新后导出");
      var relation = new Relation(current.getSupplierCode(), current.getSupplierName(), current.getSupplyRatio(), current.getSelectionRule());
      if (group.putIfAbsent(identity(relation), relation) != null) {
        throw new IllegalArgumentException("该料号供应商存在多个当前版本，请先核对供货比率表：" + codes.get(code));
      }
    }
    // 只有完整名称唯一对应一个真实代码时，才合并旧的无代码关系；不能猜供应商代码。
    Map<String, LinkedHashSet<String>> supplierCodesByName = new LinkedHashMap<>();
    for (var review : reviews) for (var candidate : review.candidates()) {
      var price = candidate.price();
      if (!key(price.supplierCode()).isEmpty()) supplierCodesByName
          .computeIfAbsent(key(review.scope().materialCode()) + ":" + key(price.supplierName()), ignored -> new LinkedHashSet<>())
          .add(key(price.supplierCode()));
    }
    for (var review : reviews) {
      String code = key(review.scope().materialCode());
      var group = groups.get(code);
      for (var candidate : review.candidates()) {
        var price = candidate.price();
        var relation = new Relation(price.supplierCode(), price.supplierName(), null, null);
        if (group.containsKey(identity(relation))) continue;
        String supplierName = key(price.supplierName());
        var sameName = group.values().stream()
            .filter(r -> !key(r.supplierName()).isEmpty() && key(r.supplierName()).equals(supplierName))
            .toList();
        if (key(relation.supplierCode()).isEmpty() && sameName.size() == 1) continue;
        String nameIdentity = "NAME:" + key(relation.supplierName());
        if (!key(relation.supplierCode()).isEmpty() && group.containsKey(nameIdentity)
            && sameName.size() == 1
            && supplierCodesByName.getOrDefault(code + ":" + key(relation.supplierName()), new LinkedHashSet<>()).size() == 1) {
          var legacy = group.remove(nameIdentity);
          relation = new Relation(price.supplierCode(), legacy.supplierName(), legacy.ratio(), legacy.selectionRule());
        }
        group.put(identity(relation), relation);
      }
    }
    return workbook(codes, materials, groups, messages);
  }

  private boolean sameMaterial(SupplierPriceReviewMaterials.Material a, SupplierPriceReviewMaterials.Material b) {
    return Objects.equals(a.materialName(), b.materialName()) && Objects.equals(a.specification(), b.specification())
        && Objects.equals(a.model(), b.model()) && Objects.equals(a.unit(), b.unit())
        && Objects.equals(a.materialShape(), b.materialShape());
  }

  private String key(String value) {
    return SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(value).toUpperCase(Locale.ROOT);
  }

  private String identity(Relation row) {
    return key(SupplierSupplyRatioNormalizeUtils.supplierIdentity(row.supplierCode(), row.supplierName()));
  }

  private byte[] workbook(Map<String, String> codes, Map<String, SupplierPriceReviewMaterials.Material> materials,
      Map<String, Map<String, Relation>> groups, Map<String, LinkedHashSet<String>> messages) throws IOException {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("供货比率补齐");
      var headerStyle = book.createCellStyle();
      headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
      headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      var font = book.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
      headerStyle.setFont(font);
      var textStyle = book.createCellStyle(); textStyle.setDataFormat(book.createDataFormat().getFormat("@"));
      textStyle.setWrapText(true);
      var ratioStyle = book.createCellStyle(); ratioStyle.setDataFormat(book.createDataFormat().getFormat("0.0000%"));
      ratioStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
      ratioStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      ratioStyle.setBorderBottom(BorderStyle.HAIR);
      var headers = List.of("物料代码", "物料名称", "规格", "型号", "单位", "物料形态属性", "供应商代码", "供应商名称", "供货比例", "说明", "取价规则");
      var header = sheet.createRow(0); header.setHeightInPoints(28);
      for (int i = 0; i < headers.size(); i++) {
        var cell = header.createCell(i); cell.setCellValue(headers.get(i)); cell.setCellStyle(headerStyle);
      }
      int rowIndex = 1;
      for (var entry : groups.entrySet()) {
        var material = materials.get(entry.getKey());
        for (var relation : entry.getValue().values()) {
          var row = sheet.createRow(rowIndex++); row.setHeightInPoints(32);
          String[] values = {codes.get(entry.getKey()), material.materialName(), material.specification(),
              material.model(), material.unit(), material.materialShape(), relation.supplierCode(),
              relation.supplierName(), null, String.join("；", messages.get(entry.getKey())), relation.selectionRule()};
          for (int i = 0; i < values.length; i++) {
            var cell = row.createCell(i); cell.setCellStyle(i == 8 ? ratioStyle : textStyle);
            if (values[i] != null) cell.setCellValue(values[i]);
          }
          if (relation.ratio() != null) row.getCell(8).setCellValue(relation.ratio().doubleValue());
        }
      }
      if (rowIndex == 1) throw new IllegalArgumentException("当前没有可导出的供应商关系");
      int[] widths = {21, 30, 48, 48, 12, 20, 20, 38, 18, 50, 32};
      for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
      sheet.createFreezePane(1, 1);
      sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, headers.size() - 1));
      var drawing = sheet.createDrawingPatriarch();
      var comment = drawing.createCellComment(book.getCreationHelper().createClientAnchor());
      comment.setAuthor("财务导入说明");
      comment.setString(book.getCreationHelper().createRichTextString(
          "黄色列填写0%至100%的供货比例；空白为未维护，0%为明确退出供货。\n"
          + "保留同一料号完整供应商集合，补齐后到供货比率表导入。名称缺失时也需补齐；型号未维护时保留空白。\n"
          + "本表包含待确认和已确认最高价的物料，以及其当前全部供货关系。导出不会确认审核、重算或修改已保存成本。"));
      header.getCell(8).setCellComment(comment);
      book.write(output);
      return output.toByteArray();
    }
  }
}
