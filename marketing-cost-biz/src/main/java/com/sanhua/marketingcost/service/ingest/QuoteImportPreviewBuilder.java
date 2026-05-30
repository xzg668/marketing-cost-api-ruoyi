package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.AccountingContext;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.FeeSummary;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.HeaderSummary;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.ItemSummary;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedDocument;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedExtraFee;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedHeader;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedItem;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationWarning;
import com.sanhua.marketingcost.enums.QuoteClassificationStatus;
import com.sanhua.marketingcost.enums.QuoteIngestStatus;
import org.springframework.util.StringUtils;

public class QuoteImportPreviewBuilder {
  private final QuoteNormalizeService quoteNormalizeService;

  public QuoteImportPreviewBuilder(QuoteNormalizeService quoteNormalizeService) {
    this.quoteNormalizeService = quoteNormalizeService;
  }

  public QuoteExcelImportPreviewResponse buildPreview(QuoteParsedImport parsed) {
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
    response.setAccountingContext(toAccountingContext(normalized));
    response.setHeaderSummary(toHeaderSummary(header));
    response.setItems(normalized.getItems().stream().map(this::toItemSummary).toList());
    response.setExtraFees(normalized.getExtraFees().stream().map(this::toFeeSummary).toList());
    if (header != null && (request.getHeader() == null || !StringUtils.hasText(request.getHeader().getGoldPrice()))) {
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

  private AccountingContext toAccountingContext(QuoteNormalizedDocument normalized) {
    AccountingContext context = new AccountingContext();
    QuoteNormalizedHeader header = normalized.getHeader();
    if (header != null) {
      context.setBusinessUnitType(header.getBusinessUnitType());
      context.setAccountingPeriodMonth(header.getAccountingPeriodMonth());
      context.setExpenseProductCategory(header.getExpenseProductCategory());
      context.setSourceCompany(header.getSourceCompany());
      context.setSourceBusinessDivision(header.getSourceBusinessDivision());
      context.setCustomer(header.getCustomer());
      context.setProductAttr(header.getProductAttr());
      context.setQuoteScenario(header.getQuoteScenario());
      context.setClassificationStatus(header.getClassificationStatus());
    }
    if (normalized.getClassification() != null) {
      context.setRuleCode(normalized.getClassification().getRuleCode());
      context.setConfidence(normalized.getClassification().getConfidence());
    }
    return context;
  }

  private HeaderSummary toHeaderSummary(QuoteNormalizedHeader header) {
    if (header == null) {
      return null;
    }
    HeaderSummary summary = new HeaderSummary();
    summary.setSourceType(header.getSourceType());
    summary.setSourceSystem(header.getSourceSystem());
    summary.setExternalFormNo(header.getExternalFormNo());
    summary.setOaNo(header.getOaNo());
    summary.setProcessCode(header.getProcessCode());
    summary.setProcessName(header.getProcessName());
    summary.setFormType(header.getFormType());
    summary.setApplyDate(header.getApplyDate());
    summary.setCustomer(header.getCustomer());
    summary.setApplicantUnit(header.getApplicantUnit());
    summary.setSourceCompany(header.getSourceCompany());
    summary.setSourceBusinessDivision(header.getSourceBusinessDivision());
    summary.setExpenseProductCategory(header.getExpenseProductCategory());
    summary.setApplicantDept(header.getApplicantDept());
    summary.setApplicantOffice(header.getApplicantOffice());
    summary.setApplicantName(header.getApplicantName());
    summary.setUrgency(header.getUrgency());
    summary.setProductAttr(header.getProductAttr());
    summary.setPriceLinkMode(header.getPriceLinkMode());
    summary.setOverseasSalesMode(header.getOverseasSalesMode());
    summary.setTradeTerms(header.getTradeTerms());
    summary.setExchangeRate(header.getExchangeRate());
    summary.setCopperPrice(header.getCopperPrice());
    summary.setZincPrice(header.getZincPrice());
    summary.setAluminumPrice(header.getAluminumPrice());
    summary.setSteelPrice(header.getSteelPrice());
    summary.setSus304Price(header.getSus304Price());
    summary.setSus316lPrice(header.getSus316lPrice());
    summary.setSilverPrice(header.getSilverPrice());
    summary.setGoldPrice(header.getGoldPrice());
    summary.setRemark(header.getRemark());
    return summary;
  }

  private ItemSummary toItemSummary(QuoteNormalizedItem item) {
    ItemSummary summary = new ItemSummary();
    summary.setExternalLineId(item.getExternalLineId());
    summary.setSeq(item.getSeq());
    summary.setProductName(item.getProductName());
    summary.setCustomerDrawing(item.getCustomerDrawing());
    summary.setCustomerCode(item.getCustomerCode());
    summary.setMaterialNo(item.getMaterialNo());
    summary.setSunlModel(item.getSunlModel());
    summary.setSpec(item.getSpec());
    summary.setProductAttr(item.getProductAttr());
    summary.setBusinessType(item.getBusinessType());
    summary.setSupportQty(item.getSupportQty());
    summary.setAnnualVolume(item.getAnnualVolume());
    summary.setScrapRate(item.getScrapRate());
    summary.setUnitLaborCost(item.getUnitLaborCost());
    summary.setTotalWithShip(item.getTotalWithShip());
    summary.setTotalNoShip(item.getTotalNoShip());
    summary.setMaterialCost(item.getMaterialCost());
    summary.setLaborCost(item.getLaborCost());
    summary.setManufacturingCost(item.getManufacturingCost());
    summary.setManagementCost(item.getManagementCost());
    summary.setValidMonth(item.getValidMonth());
    summary.setSus304WeightG(item.getSus304WeightG());
    summary.setSus316WeightG(item.getSus316WeightG());
    summary.setCopperWeightG(item.getCopperWeightG());
    summary.setClassificationStatus(item.getClassificationStatus());
    summary.setQuoteScenario(item.getQuoteScenario());
    summary.setBusinessUnitType(item.getBusinessUnitType());
    summary.setValidDate(item.getValidDate());
    return summary;
  }

  private FeeSummary toFeeSummary(QuoteNormalizedExtraFee fee) {
    FeeSummary summary = new FeeSummary();
    summary.setScope(fee.getScope());
    summary.setItemSeq(fee.getItemSeq());
    summary.setExternalLineId(fee.getExternalLineId());
    summary.setFeeCode(fee.getFeeCode());
    summary.setFeeName(fee.getFeeName());
    summary.setFeeCategory(fee.getFeeCategory());
    summary.setAmount(fee.getAmount());
    summary.setUnit(fee.getUnit());
    summary.setRemark(fee.getRemark());
    summary.setSourceFieldName(fee.getSourceFieldName());
    summary.setSourceFieldPath(fee.getSourceFieldPath());
    return summary;
  }
}
