package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationError;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuoteExcelImportServiceImpl implements QuoteExcelImportService {
  private static final String HEADER_SHEET = "报价单表头";
  private static final String ITEM_SHEET = "产品明细";
  private static final String FEE_SHEET = "额外费用";

  private final QuoteIngestService quoteIngestService;
  private final QuoteImportPreviewBuilder previewBuilder;
  private final QuoteOaFormExcelParser oaFormExcelParser;

  public QuoteExcelImportServiceImpl(
      QuoteNormalizeService quoteNormalizeService, QuoteIngestService quoteIngestService) {
    this.quoteIngestService = quoteIngestService;
    this.previewBuilder = new QuoteImportPreviewBuilder(quoteNormalizeService);
    this.oaFormExcelParser = new QuoteOaFormExcelParser(new QuoteOaFormMappingReader());
  }

  @Override
  public QuoteExcelImportPreviewResponse preview(InputStream inputStream, String fileName) {
    QuoteParsedExcel parsed = parse(inputStream, fileName);
    return previewBuilder.buildPreview(parsed);
  }

  @Override
  public QuoteExcelImportCommitResponse commit(InputStream inputStream, String fileName) {
    QuoteParsedExcel parsed = parse(inputStream, fileName);
    QuoteExcelImportPreviewResponse preview = previewBuilder.buildPreview(parsed);
    QuoteExcelImportCommitResponse response = new QuoteExcelImportCommitResponse();
    response.setPreview(preview);
    if (!preview.isValid()) {
      response.setCommitted(false);
      return response;
    }
    for (QuoteIngestRequest request : parsed.requests.values()) {
      QuoteIngestResponse result = quoteIngestService.ingest(request);
      response.getResults().add(result);
    }
    response.setCommitted(true);
    return response;
  }

  private QuoteParsedExcel parse(InputStream inputStream, String fileName) {
    if (inputStream == null) {
      throw new QuoteIngestException("Excel 文件不能为空");
    }
    QuoteParsedExcel parsed = new QuoteParsedExcel(fileName);
    try (Workbook workbook = WorkbookFactory.create(inputStream)) {
      DataFormatter formatter = new DataFormatter(Locale.CHINA);
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      // 当前阶段 Excel 模板模拟泛微 OA 原始表单，解析时以隐藏映射为准，不按中文标题猜字段。
      if (oaFormExcelParser.supports(workbook)) {
        return oaFormExcelParser.parse(workbook, fileName, formatter, evaluator);
      }
      readHeaders(workbook.getSheet(HEADER_SHEET), formatter, evaluator, parsed);
      readItems(workbook.getSheet(ITEM_SHEET), formatter, evaluator, parsed);
      readFees(workbook.getSheet(FEE_SHEET), formatter, evaluator, parsed);
    } catch (IOException | RuntimeException ex) {
      throw new QuoteIngestException("Excel 解析失败: " + ex.getMessage());
    }
    parsed.itemCount = parsed.requests.values().stream().mapToInt(r -> r.getItems().size()).sum();
    return parsed;
  }

  private void readHeaders(
      Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, QuoteParsedExcel parsed) {
    if (sheet == null) {
      parsed.errors.add(new QuoteValidationError(HEADER_SHEET, "SHEET_REQUIRED", "缺少报价单表头 Sheet"));
      return;
    }
    Map<String, Integer> columns = readColumns(sheet.getRow(0), formatter, evaluator);
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (isBlankRow(row, formatter, evaluator)) {
        continue;
      }
      String oaNo = cell(row, columns, "报价单号", formatter, evaluator);
      QuoteIngestRequest request = new QuoteIngestRequest();
      request.setSourceType(defaultValue(cell(row, columns, "来源类型", formatter, evaluator), "EXCEL"));
      request.setSourceSystem("EXCEL_TEMPLATE");
      request.setOaNo(oaNo);
      request.setExternalFormNo(oaNo);
      request.setVersion("1");
      if (StringUtils.hasText(oaNo)) {
        request.setIdempotencyKey("EXCEL:" + oaNo.trim() + ":1");
      }
      request.setHeader(readHeader(row, columns, formatter, evaluator));
      request.setRawPayload(Map.of("fileName", nullToEmpty(parsed.fileName), "headerRow", rowIndex + 1));
      parsed.requests.put(requestKey(oaNo, rowIndex), request);
      parsed.formCount++;
    }
  }

  private QuoteIngestHeaderRequest readHeader(
      Row row, Map<String, Integer> columns, DataFormatter formatter, FormulaEvaluator evaluator) {
    QuoteIngestHeaderRequest header = new QuoteIngestHeaderRequest();
    header.setProcessCode(cell(row, columns, "流程编号", formatter, evaluator));
    header.setProcessName(cell(row, columns, "流程名称", formatter, evaluator));
    header.setApplyDate(cell(row, columns, "申请日期", formatter, evaluator));
    header.setCustomer(cell(row, columns, "客户名称", formatter, evaluator));
    header.setApplicantUnit(cell(row, columns, "申请单位", formatter, evaluator));
    header.setSourceCompany(cell(row, columns, "所属公司", formatter, evaluator));
    header.setSourceBusinessDivision(cell(row, columns, "所属事业部", formatter, evaluator));
    header.setExpenseProductCategory(cell(row, columns, "费用产品大类", formatter, evaluator));
    header.setApplicantDept(cell(row, columns, "申请部门", formatter, evaluator));
    header.setApplicantOffice(cell(row, columns, "申请处室", formatter, evaluator));
    header.setApplicantName(cell(row, columns, "申请人", formatter, evaluator));
    header.setUrgency(cell(row, columns, "紧急程度", formatter, evaluator));
    header.setProductAttr(cell(row, columns, "产品属性", formatter, evaluator));
    header.setPriceLinkMode(cell(row, columns, "销售价格联动情况", formatter, evaluator));
    header.setOverseasSalesMode(cell(row, columns, "是否海外销售", formatter, evaluator));
    header.setTradeTerms(cell(row, columns, "贸易条款", formatter, evaluator));
    header.setExchangeRate(cell(row, columns, "汇率", formatter, evaluator));
    header.setCopperPrice(cell(row, columns, "铜基价", formatter, evaluator));
    header.setZincPrice(cell(row, columns, "锌基价", formatter, evaluator));
    header.setAluminumPrice(cell(row, columns, "铝基价", formatter, evaluator));
    header.setSteelPrice(cell(row, columns, "不锈钢基价", formatter, evaluator));
    header.setSus304Price(cell(row, columns, "SUS304 基价", formatter, evaluator));
    header.setSus316lPrice(cell(row, columns, "SUS316L 基价", formatter, evaluator));
    header.setSilverPrice(cell(row, columns, "白银基价", formatter, evaluator));
    header.setGoldPrice(cell(row, columns, "黄金基价", formatter, evaluator));
    header.setRemark(cell(row, columns, "备注", formatter, evaluator));
    return header;
  }

  private void readItems(
      Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, QuoteParsedExcel parsed) {
    if (sheet == null) {
      parsed.errors.add(new QuoteValidationError(ITEM_SHEET, "SHEET_REQUIRED", "缺少产品明细 Sheet"));
      return;
    }
    Map<String, Integer> columns = readColumns(sheet.getRow(0), formatter, evaluator);
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (isBlankRow(row, formatter, evaluator)) {
        continue;
      }
      String oaNo = cell(row, columns, "报价单号", formatter, evaluator);
      QuoteIngestRequest request = findRequest(parsed, oaNo, rowIndex + 1, ITEM_SHEET);
      if (request == null) {
        continue;
      }
      request.getItems().add(readItem(row, columns, formatter, evaluator));
    }
  }

  private QuoteIngestItemRequest readItem(
      Row row, Map<String, Integer> columns, DataFormatter formatter, FormulaEvaluator evaluator) {
    QuoteIngestItemRequest item = new QuoteIngestItemRequest();
    item.setSeq(parseInteger(cell(row, columns, "行号", formatter, evaluator)));
    item.setBusinessType(cell(row, columns, "业务类型", formatter, evaluator));
    item.setProductName(cell(row, columns, "产品名称", formatter, evaluator));
    item.setCustomerDrawing(cell(row, columns, "客户图号", formatter, evaluator));
    item.setCustomerCode(cell(row, columns, "客户编码", formatter, evaluator));
    item.setMaterialNo(cell(row, columns, "料号", formatter, evaluator));
    item.setSunlModel(cell(row, columns, "三花型号", formatter, evaluator));
    item.setSpec(cell(row, columns, "规格", formatter, evaluator));
    item.setProductAttr(cell(row, columns, "产品属性", formatter, evaluator));
    item.setFirstQuoteFlag(parseBoolean(cell(row, columns, "是否首次报价", formatter, evaluator)));
    item.setCertificationRequired(parseBoolean(cell(row, columns, "是否有认证需求", formatter, evaluator)));
    item.setOriginCountry(cell(row, columns, "起运国", formatter, evaluator));
    item.setTechnicianName(cell(row, columns, "技术员", formatter, evaluator));
    item.setPackageType(cell(row, columns, "包装类型", formatter, evaluator));
    item.setPackageMethod(cell(row, columns, "包装方式", formatter, evaluator));
    item.setPackageComponentCode(cell(row, columns, "包装组件料号", formatter, evaluator));
    item.setPackageQty(cell(row, columns, "包装数量", formatter, evaluator));
    item.setSupportQty(cell(row, columns, "三花配套量", formatter, evaluator));
    item.setAnnualVolume(cell(row, columns, "预计年用量", formatter, evaluator));
    item.setProjectNo(cell(row, columns, "研发项目号", formatter, evaluator));
    item.setProductStatus(cell(row, columns, "产品状态", formatter, evaluator));
    item.setScrapRate(cell(row, columns, "报废率", formatter, evaluator));
    item.setUnitLaborCost(cell(row, columns, "单件工资", formatter, evaluator));
    item.setTotalWithShip(cell(row, columns, "含运输费总成本", formatter, evaluator));
    item.setTotalNoShip(cell(row, columns, "不含运输费总成本", formatter, evaluator));
    item.setMaterialCost(cell(row, columns, "直接材料费", formatter, evaluator));
    item.setLaborCost(cell(row, columns, "直接人工费", formatter, evaluator));
    item.setManufacturingCost(cell(row, columns, "制造费用", formatter, evaluator));
    item.setManagementCost(cell(row, columns, "企业管理费", formatter, evaluator));
    item.setValidMonth(firstCell(row, columns, formatter, evaluator, "成本有效期(月)"));
    item.setValidDate(firstCell(row, columns, formatter, evaluator, "成本失效日期", "失效日期", "有效期至", "成本有效期"));
    item.setSus304WeightG(firstCell(row, columns, formatter, evaluator, "不锈钢SUS304(克)", "SUS304(克)"));
    item.setSus316WeightG(firstCell(row, columns, formatter, evaluator, "不锈钢SUS316(克)", "SUS316(克)"));
    item.setCopperWeightG(firstCell(row, columns, formatter, evaluator, "铜重(克)", "铜重量(克)"));
    return item;
  }

  private void readFees(
      Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, QuoteParsedExcel parsed) {
    if (sheet == null) {
      return;
    }
    Map<String, Integer> columns = readColumns(sheet.getRow(0), formatter, evaluator);
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (isBlankRow(row, formatter, evaluator)) {
        continue;
      }
      String oaNo = cell(row, columns, "报价单号", formatter, evaluator);
      QuoteIngestRequest request = findRequest(parsed, oaNo, rowIndex + 1, FEE_SHEET);
      if (request == null) {
        continue;
      }
      QuoteExtraFeeRequest fee = readFee(row, columns, formatter, evaluator);
      Integer seq = parseInteger(cell(row, columns, "行号", formatter, evaluator));
      if (seq == null) {
        request.getExtraFees().add(fee);
      } else {
        QuoteIngestItemRequest item = findItemBySeq(request, seq);
        if (item == null) {
          parsed
              .errors
              .add(
                  new QuoteValidationError(
                      FEE_SHEET + ".行号",
                      "FEE_ITEM_NOT_FOUND",
                      "额外费用指定的产品行不存在",
                      rowIndex + 1));
        } else {
          item.getExtraFees().add(fee);
        }
      }
      parsed.feeCount++;
    }
  }

  private QuoteExtraFeeRequest readFee(
      Row row, Map<String, Integer> columns, DataFormatter formatter, FormulaEvaluator evaluator) {
    QuoteExtraFeeRequest fee = new QuoteExtraFeeRequest();
    fee.setFeeCode(cell(row, columns, "费用编码", formatter, evaluator));
    fee.setFeeName(cell(row, columns, "费用名称", formatter, evaluator));
    fee.setFeeCategory(cell(row, columns, "费用分类", formatter, evaluator));
    fee.setAmount(cell(row, columns, "金额", formatter, evaluator));
    fee.setUnit(cell(row, columns, "单位", formatter, evaluator));
    fee.setRemark(cell(row, columns, "备注", formatter, evaluator));
    fee.setSourceFieldName(FEE_SHEET);
    return fee;
  }

  private QuoteIngestRequest findRequest(
      QuoteParsedExcel parsed, String oaNo, int rowNo, String sheetName) {
    if (!StringUtils.hasText(oaNo)) {
      parsed
          .errors
          .add(new QuoteValidationError(sheetName + ".报价单号", "FORM_NO_REQUIRED", "报价单号不能为空", rowNo));
      return null;
    }
    QuoteIngestRequest request = parsed.requests.get(oaNo.trim());
    if (request == null) {
      parsed
          .errors
          .add(new QuoteValidationError(sheetName + ".报价单号", "HEADER_NOT_FOUND", "未找到对应报价单表头", rowNo));
    }
    return request;
  }

  private QuoteIngestItemRequest findItemBySeq(QuoteIngestRequest request, Integer seq) {
    for (QuoteIngestItemRequest item : request.getItems()) {
      if (seq.equals(item.getSeq())) {
        return item;
      }
    }
    return null;
  }

  private Map<String, Integer> readColumns(
      Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    Map<String, Integer> columns = new LinkedHashMap<>();
    if (row == null) {
      return columns;
    }
    for (Cell cell : row) {
      String value = cellValue(cell, formatter, evaluator);
      if (StringUtils.hasText(value)) {
        columns.put(value.trim(), cell.getColumnIndex());
      }
    }
    return columns;
  }

  private boolean isBlankRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (row == null) {
      return true;
    }
    for (Cell cell : row) {
      if (StringUtils.hasText(cellValue(cell, formatter, evaluator))) {
        return false;
      }
    }
    return true;
  }

  private String cell(
      Row row,
      Map<String, Integer> columns,
      String columnName,
      DataFormatter formatter,
      FormulaEvaluator evaluator) {
    Integer column = columns.get(columnName);
    if (row == null || column == null) {
      return null;
    }
    return cellValue(row.getCell(column), formatter, evaluator);
  }

  private String firstCell(
      Row row,
      Map<String, Integer> columns,
      DataFormatter formatter,
      FormulaEvaluator evaluator,
      String... columnNames) {
    if (columnNames == null) {
      return null;
    }
    for (String columnName : columnNames) {
      String value = cell(row, columns, columnName, formatter, evaluator);
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private String cellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (cell == null) {
      return null;
    }
    String value = formatter.formatCellValue(cell, evaluator);
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private Integer parseInteger(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return Integer.valueOf(value.replace(",", "").replace(".0", "").trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Boolean parseBoolean(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim();
    if ("是".equals(normalized)
        || "Y".equalsIgnoreCase(normalized)
        || "YES".equalsIgnoreCase(normalized)
        || "TRUE".equalsIgnoreCase(normalized)
        || "1".equals(normalized)) {
      return true;
    }
    if ("否".equals(normalized)
        || "N".equalsIgnoreCase(normalized)
        || "NO".equalsIgnoreCase(normalized)
        || "FALSE".equalsIgnoreCase(normalized)
        || "0".equals(normalized)) {
      return false;
    }
    return null;
  }

  private String defaultValue(String value, String defaultValue) {
    return StringUtils.hasText(value) ? value.trim() : defaultValue;
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private String requestKey(String oaNo, int rowIndex) {
    return StringUtils.hasText(oaNo) ? oaNo.trim() : "$ROW_" + rowIndex;
  }

}
