package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricePrepareGapPresentationTest {
  @Test void mixedRealPriceAndManufacturingRatioGapsHaveOneConsistentMeaning() {
    var ratio = gap("301260158", "PRICE_SOURCE_ERROR", "RAW_PRICE_ERROR");
    var raw = gap("301070080", "MISSING_PRICE", "MISSING_RAW_PRICE");
    var response = response(List.of(ratio, raw, gap("301070080", "MISSING_PRICE", "MISSING_RAW_PRICE")), List.of(review("301260158")));
    String text = PricePrepareGapPresentation.summary(response, "旧的泛化提示");
    assertThat(text).contains("缺少供货比率：301260158", "缺少原料价格：301070080").doesNotContain("取价异常");
    assertThat(text.indexOf("301070080")).isEqualTo(text.lastIndexOf("301070080"));
    assertThat(ratio.getDisplayReasonCode()).isEqualTo("SUPPLIER_RATIO_REVIEW_REQUIRED");
    assertThat(ratio.getDisplayGapType()).isEqualTo("缺少供货比率");
    assertThat(ratio.getDisplayMessage()).contains("已有多个供应商报价", "供货比率");
    assertThat(ratio.getGapType()).isEqualTo("PRICE_SOURCE_ERROR");
    assertThat(ratio.getReasonCode()).isEqualTo("RAW_PRICE_ERROR");
    assertThat(PricePrepareGapPresentation.blockingCode(response)).isEqualTo("PRICE_AND_SUPPLIER_RATIO_MISSING");
  }
  @Test void ratioOnlyCannotBeLabelledMissingPrice() {
    var r=response(List.of(gap("A","MISSING_PRICE","WAITING")),List.of(review("A")));
    assertThat(PricePrepareGapPresentation.blockingCode(r)).isEqualTo("SUPPLIER_RATIO_REVIEW_REQUIRED");
    assertThat(PricePrepareGapPresentation.summary(r,"missing")).contains("缺少供货比率").doesNotContain("缺少可用价格");
  }
  @Test void unrelatedReviewCannotHideRealError() {
    var r=response(List.of(gap("A","PRICE_SOURCE_ERROR","RAW_PRICE_ERROR")),List.of(review("B")));
    PricePrepareGapPresentation.enrich(r);
    assertThat(r.getGaps().getRecords().getFirst().getDisplayGapType()).isEqualTo("取价异常");
    assertThat(PricePrepareGapPresentation.blockingCode(r)).isEqualTo("PRICE_MISSING");
  }
  @Test void confirmedHighPriceDoesNotRemainARatioBlock() {
    var old=review("A");var confirmed=new SupplierPriceReview(old.scopeKey(),old.fingerprint(),old.scope(),old.candidates(),"FALLBACK_HIGH","财务",null,"已确认");
    var r=response(List.of(gap("A","PRICE_SOURCE_ERROR","RAW_PRICE_ERROR")),List.of(confirmed));
    assertThat(PricePrepareGapPresentation.summary(r,"missing")).contains("取价异常").doesNotContain("供货比率");
  }
  @Test void waitingForPublicationIsNotMisrepresentedAsMissingPriceOrRatio() {
    var r=response(List.of(gap("A","MISSING_PRICE","WAITING")),List.of());
    assertThat(PricePrepareGapPresentation.summary(r,"missing")).contains("价格待就绪").doesNotContain("缺少可用价格","供货比率");
  }
  @Test void missingScrapAndRawPriceRemainDistinct() {
    var r=response(List.of(gap("A","MISSING_PRICE","MISSING_RAW_PRICE"),gap("S","MISSING_PRICE","MISSING_SCRAP_PRICE")),List.of());
    assertThat(PricePrepareGapPresentation.summary(r,"missing")).contains("缺少原料价格：A","缺少废料价格：S");
  }
  @Test void legacyResponsesRetainTheirActualFailureMessage() {
    assertThat(PricePrepareGapPresentation.summary(new QuotePricePrepareWorkbenchResponse(),"财务基价未发布")).isEqualTo("财务基价未发布");
    assertThat(PricePrepareGapPresentation.summary(null,"数据库连接中断")).isEqualTo("数据库连接中断");
  }
  private PricePrepareGap gap(String code,String type,String reason) {var g=new PricePrepareGap();g.setGapMaterialCode(code);g.setGapType(type);g.setReasonCode(reason);g.setMessage("真实原始原因");return g;}
  private QuotePricePrepareWorkbenchResponse response(List<PricePrepareGap> gaps,List<SupplierPriceReview> reviews) {var r=new QuotePricePrepareWorkbenchResponse();r.setGaps(new PricePrepareGapPageResponse(gaps.size(),gaps));r.setSupplierPriceReviews(reviews);return r;}
  private SupplierPriceReview review(String code) {
    var scope=new SupplierPriceScope("COMMERCIAL","OA",1L,"2026-11",LocalDate.of(2026,11,7),"220",code,"LINKED");
    return new SupplierPriceSelectionService(null,null).selectPrepared(scope,List.of(
        new SupplierPriceCandidate("A","S1","甲",new BigDecimal("10"),null,null),
        new SupplierPriceCandidate("B","S2","乙",new BigDecimal("12"),null,null)),List.of(),null).review();
  }
}
