package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedDocument;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedHeader;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationError;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationWarning;
import com.sanhua.marketingcost.enums.QuoteClassificationStatus;
import com.sanhua.marketingcost.enums.QuoteIngestStatus;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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

  private final QuoteNormalizeService quoteNormalizeService;
  private final QuoteIngestService quoteIngestService;

  public QuoteExcelImportServiceImpl(
      QuoteNormalizeService quoteNormalizeService, QuoteIngestService quoteIngestService) {
    this.quoteNormalizeService = quoteNormalizeService;
    this.quoteIngestService = quoteIngestService;
  }

  @Override
  public QuoteExcelImportPreviewResponse preview(InputStream inputStream, String fileName) {
    ParsedExcel parsed = parse(inputStream, fileName);
    return buildPreview(parsed);
  }

  @Override
  public QuoteExcelImportCommitResponse commit(InputStream inputStream, String fileName) {
    ParsedExcel parsed = parse(inputStream, fileName);
    QuoteExcelImportPreviewResponse preview = buildPreview(parsed);
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

  private ParsedExcel parse(InputStream inputStream, String fileName) {
    if (inputStream == null) {
      throw new QuoteIngestException("Excel 文件不能为空");
    }
    ParsedExcel parsed = new ParsedExcel(fileName);
    try (Workbook workbook = WorkbookFactory.create(inputStream)) {
      DataFormatter formatter = new DataFormatter(Locale.CHINA);
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
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
      Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, ParsedExcel parsed) {
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
      request.setSourceSystem("EXCEL_IMPORT");
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
    header.setApplicantDept(cell(row, columns, "申请部门", formatter, evaluator));
    header.setApplicantOffice(cell(row, columns, "申请处室", formatter, evaluator));
    header.setApplicantName(cell(row, columns, "申请人", formatter, evaluator));
    header.setUrgency(cell(row, columns, "紧急程度", formatter, evaluator));
    header.setProductAttr(cell(row, columns, "产品属性", formatter, evaluator));
    header.setPriceLinkMode(cell(row, columns, "销售价格联动情况", formatter, evaluator));
    header.setOverseasSalesMode(cell(row, columns, "是否海外销售", formatter, evaluator));
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
      Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, ParsedExcel parsed) {
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
    item.setValidDate(cell(row, columns, "成本有效期", formatter, evaluator));
    return item;
  }

  private void readFees(
      Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, ParsedExcel parsed) {
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

  private QuoteExcelImportPreviewResponse buildPreview(ParsedExcel parsed) {
    QuoteExcelImportPreviewResponse response = new QuoteExcelImportPreviewResponse();
    response.setFileName(parsed.fileName);
    response.setFormCount(parsed.formCount);
    response.setItemCount(parsed.itemCount);
    response.setFeeCount(parsed.feeCount);
    response.getErrors().addAll(parsed.errors);

    for (QuoteIngestRequest request : parsed.requests.values()) {
      QuoteIngestPreviewResponse form = previewOne(request);
      response.getForms().add(form);
      response.getErrors().addAll(form.getErrors());
      response.getWarnings().addAll(form.getWarnings());
    }
    response.setValid(response.getErrors().isEmpty());
    return response;
  }

  private QuoteIngestPreviewResponse previewOne(QuoteIngestRequest request) {
    QuoteNormalizedDocument normalized = quoteNormalizeService.normalize(request);
    QuoteNormalizedHeader header = normalized.getHeader();
    QuoteIngestPreviewResponse response = new QuoteIngestPreviewResponse();
    response.setValid(normalized.getErrors().isEmpty());
    response.setAccepted(response.isValid());
    response.setSourceType(header == null ? null : header.getSourceType());
    response.setExternalFormNo(header == null ? null : header.getExternalFormNo());
    response.setOaNo(header == null ? null : header.getOaNo());
    response.setProcessCode(header == null ? null : header.getProcessCode());
    response.setQuoteScenario(header == null ? null : header.getQuoteScenario());
    response.setClassificationStatus(header == null ? null : header.getClassificationStatus());
    response.setItemCount(normalized.getItems().size());
    response.setErrors(normalized.getErrors());
    response.setWarnings(normalized.getWarnings());
    if (header != null && !StringUtils.hasText(request.getHeader().getGoldPrice())) {
      response
          .getWarnings()
          .add(
              new QuoteValidationWarning(
                  "header.goldPrice", "GOLD_PRICE_EMPTY", "黄金基价为空，如报价涉及黄金材料需补充"));
    }
    boolean pending =
        header != null
            && QuoteClassificationStatus.PENDING.getCode().equals(header.getClassificationStatus());
    response.setClassificationPending(pending);
    if (!response.isValid()) {
      response.setIngestStatus(QuoteIngestStatus.REJECTED.getCode());
    } else if (pending) {
      response.setIngestStatus(QuoteIngestStatus.CLASSIFY_PENDING.getCode());
    } else {
      response.setIngestStatus(QuoteIngestStatus.RECEIVED.getCode());
    }
    return response;
  }

  private QuoteIngestRequest findRequest(
      ParsedExcel parsed, String oaNo, int rowNo, String sheetName) {
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

  private static final class ParsedExcel {
    private final String fileName;
    private final Map<String, QuoteIngestRequest> requests = new LinkedHashMap<>();
    private final List<QuoteValidationError> errors = new ArrayList<>();
    private int formCount;
    private int itemCount;
    private int feeCount;

    private ParsedExcel(String fileName) {
      this.fileName = fileName;
    }
  }
}
