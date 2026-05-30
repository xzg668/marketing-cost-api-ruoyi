package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationError;
import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import com.sanhua.marketingcost.enums.QuoteSourceType;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuotePdfImportServiceImpl implements QuotePdfImportService {
  private static final String SOURCE_SYSTEM_OA_PDF = "OA_PDF";
  private static final Pattern OA_NO_PATTERN = Pattern.compile("(FI-[A-Z]{2}-\\d{3}-\\d{8}-\\d+)");

  private final QuoteIngestService quoteIngestService;
  private final QuoteImportPreviewBuilder previewBuilder;
  private final QuotePdfTextExtractor textExtractor;
  private final QuoteOaPdfTemplateResolver templateResolver;
  private final QuoteOaPdfHeaderParser headerParser;
  private final QuoteOaPdfDetailTableParser detailTableParser;

  public QuotePdfImportServiceImpl(
      QuoteNormalizeService quoteNormalizeService,
      QuoteIngestService quoteIngestService,
      QuotePdfTextExtractor textExtractor) {
    this.quoteIngestService = quoteIngestService;
    this.previewBuilder = new QuoteImportPreviewBuilder(quoteNormalizeService);
    this.textExtractor = textExtractor;
    this.templateResolver = new QuoteOaPdfTemplateResolver();
    this.headerParser = new QuoteOaPdfHeaderParser();
    this.detailTableParser = new QuoteOaPdfDetailTableParser();
  }

  @Override
  public QuoteExcelImportPreviewResponse preview(InputStream inputStream, String fileName) {
    QuoteParsedPdf parsed = parse(inputStream, fileName);
    return previewBuilder.buildPreview(parsed);
  }

  @Override
  public QuoteExcelImportCommitResponse commit(InputStream inputStream, String fileName) {
    QuoteParsedPdf parsed = parse(inputStream, fileName);
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

  private QuoteParsedPdf parse(InputStream inputStream, String fileName) {
    QuoteParsedPdf parsed = new QuoteParsedPdf(fileName);
    try {
      parsed.document = textExtractor.extract(inputStream, fileName);
      QuoteExcelTemplateType templateType =
          templateResolver.resolve(fileName, parsed.document == null ? null : parsed.document.getFullText());
      QuoteOaPdfTemplateDefinition template = QuoteOaPdfTemplateDefinitions.get(templateType);
      if (template == null) {
        parsed.errors.add(new QuoteValidationError("pdf.template", "PDF_TEMPLATE_UNSUPPORTED", "暂不支持该 OA PDF 模板"));
        return parsed;
      }
      QuotePdfParseContext context = new QuotePdfParseContext();
      context.setFileName(fileName);
      context.setDocument(parsed.document);
      context.setTemplateDefinition(template);
      QuoteIngestRequest request = toTemplateSkeletonRequest(context);
      headerParser.parse(context, request);
      detailTableParser.parse(context, request);
      applyOaNoDerivedFields(request);
      parsed.requests.put(requestKey(request, template), request);
      parsed.formCount = 1;
      parsed.itemCount = request.getItems().size();
      parsed.feeCount =
          request.getExtraFees().size()
              + request.getItems().stream().mapToInt(item -> item.getExtraFees().size()).sum();
    } catch (QuotePdfParseException ex) {
      parsed.errors.add(new QuoteValidationError("pdf", ex.getCode(), ex.getMessage()));
    } catch (RuntimeException ex) {
      parsed.errors.add(new QuoteValidationError("pdf", "PDF_PARSE_FAILED", "PDF 解析失败: " + ex.getMessage()));
    }
    return parsed;
  }

  private QuoteIngestRequest toTemplateSkeletonRequest(QuotePdfParseContext context) {
    QuoteOaPdfTemplateDefinition template = context.getTemplateDefinition();
    QuoteIngestRequest request = new QuoteIngestRequest();
    request.setSourceType(QuoteSourceType.OA.getCode());
    request.setSourceSystem(SOURCE_SYSTEM_OA_PDF);
    request.setVersion("1");
    String oaNo = extractOaNo(context.getDocument() == null ? null : context.getDocument().getFullText());
    if (StringUtils.hasText(oaNo)) {
      request.setOaNo(oaNo);
      request.setExternalFormNo(oaNo);
      request.setIdempotencyKey(SOURCE_SYSTEM_OA_PDF + ":" + oaNo + ":1");
    }

    QuoteIngestHeaderRequest header = new QuoteIngestHeaderRequest();
    header.setProcessCode(template.getProcessCode());
    header.setProcessName(template.getProcessName());
    header.setQuoteScenario(template.getQuoteScenario());
    header.setBusinessUnitType(template.getBusinessUnitType());
    header.setExpenseProductCategory(template.getExpenseProductCategory());
    request.setHeader(header);
    request.setRawPayload(
        Map.of(
            "fileName",
            nullToEmpty(context.getFileName()),
            "sourceFormat",
            "PDF",
            "templateType",
            template.getTemplateType().getCode()));
    return request;
  }

  private void applyOaNoDerivedFields(QuoteIngestRequest request) {
    if (!StringUtils.hasText(request.getOaNo())) {
      return;
    }
    request.setExternalFormNo(request.getOaNo());
    request.setIdempotencyKey(SOURCE_SYSTEM_OA_PDF + ":" + request.getOaNo().trim() + ":1");
  }

  private String extractOaNo(String text) {
    if (!StringUtils.hasText(text)) {
      return null;
    }
    Matcher matcher = OA_NO_PATTERN.matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  private String requestKey(QuoteIngestRequest request, QuoteOaPdfTemplateDefinition template) {
    if (StringUtils.hasText(request.getOaNo())) {
      return request.getOaNo().trim();
    }
    return template.getTemplateType().getCode();
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
