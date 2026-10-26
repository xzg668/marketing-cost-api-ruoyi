package com.sanhua.marketingcost.service.pricing.household;

import com.sanhua.marketingcost.service.pricing.monthly.TaxBasis;
import java.time.Instant;
import java.util.*;
import static com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryWorkbookParser.*;

/** Decimal API values are strings; business context and immutable preview state remain explicit. */
public final class HouseholdInquiryModels {
  private HouseholdInquiryModels() {}
  public record PriceContext(String unit,String currency,TaxBasis taxBasis) {
    public PriceContext {
      if(unit==null||unit.isBlank()||unit.strip().length()>255||currency==null||!currency.strip().toUpperCase(Locale.ROOT).matches("[A-Z]{3}")||taxBasis==null) throw new IllegalArgumentException("必须明确来源计价单位、币种和税口径");
      unit=unit.strip();currency=currency.strip().toUpperCase(Locale.ROOT);
    }
  }
  public enum Action { CREATE, NEW_VERSION, UNCHANGED }
  public record PlannedRow(SourceRow source,Long expectedCurrentId,Action action,boolean historicalValue,
      boolean priceChanged,String pricingFingerprint,String recordFingerprint) {}
  public record PreviewContent(WorkbookData workbook,String sourceMonth,String sourceMonthOrigin,
      PriceContext priceContext,List<PlannedRow> rows,List<String> warnings) {
    public PreviewContent { rows=List.copyOf(rows);warnings=List.copyOf(warnings); }
  }
  public record Preview(long batchId,String previewId,String publishRequestId,String previewFingerprint,
      Instant expiresAt,String status,PreviewContent content) {}
  public record PublishRequest(String previewId,String publishRequestId,String previewFingerprint) {}
  public record PublishedRow(String materialCode,Action action,long versionId,int revisionNo,Long previousVersionId,boolean priceChanged,boolean historicalValue) {}
  public record PublishResult(long batchId,String publishRequestId,String status,List<PublishedRow> rows,
      int mergedDuplicateRows,int newVersions,String fileSha256) {
    public PublishResult { rows=List.copyOf(rows); }
  }
  public record ImportView(Preview preview,PublishResult publication,String createdBy,java.time.LocalDateTime publishedAt) {}
  public record StoredEvidence(SourceRow source,PriceContext priceContext,String sourceMonth) {}
  public record PriceView(long id,String materialCode,String materialName,String specification,int revisionNo,
      boolean current,String unitPrice,String householdRate,String sourceMonth,PriceContext priceContext,
      long importBatchId,java.time.LocalDateTime createdAt,List<Integer> sourceRows) {}
  public record PricePage(List<PriceView> rows,long total,int page,int size) { public PricePage { rows=List.copyOf(rows); } }
  public record FinancialView(PriceView price,StoredEvidence evidence,String fileName,String fileSha256) {}
  public record OriginalFile(String fileName,String sha256,byte[] bytes) {}
}
