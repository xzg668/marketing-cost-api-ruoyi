package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeaverQuotePayloadAdapterImplTest {
  private final WeaverQuotePayloadAdapter adapter = new WeaverQuotePayloadAdapterImpl();
  private final QuoteNormalizeService normalizeService =
      new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService());

  @Test
  void adaptsMockWeaverPayloadToUnifiedIngestRequest() {
    QuoteIngestRequest request = adapter.adapt(mockPayload());

    assertThat(request.getSourceType()).isEqualTo("WEAVER_OA");
    assertThat(request.getSourceSystem()).isEqualTo("WEAVER_ECOLOGY");
    assertThat(request.getExternalFormNo()).isEqualTo("OA-W-001");
    assertThat(request.getIdempotencyKey()).isEqualTo("WEAVER_OA:OA-W-001:1");
    assertThat(request.getHeader().getProcessCode()).isEqualTo("FI-SC-006");
    assertThat(request.getHeader().getApplyDate()).isEqualTo("2026-03-27");
    assertThat(request.getHeader().getApplicantDept()).isEqualTo("欧洲业务管理部");
    assertThat(request.getItems()).hasSize(1);
    assertThat(request.getItems().get(0).getSeq()).isEqualTo(1);
    assertThat(request.getItems().get(0).getMaterialNo()).isEqualTo("1001");
    assertThat(request.getExtraFields()).extracting("fieldCode").contains("applyDateTime", "weaverExtraHeader");
    assertThat(request.getItems().get(0).getExtraFields()).extracting("fieldCode").contains("weaverExtraItem");
    assertThat(request.getExtraFees()).hasSize(1);

    QuoteNormalizedDocument normalized = normalizeService.normalize(request);
    assertThat(normalized.getErrors()).isEmpty();
    assertThat(normalized.getHeader().getAccountingPeriodMonth()).isEqualTo("2026-03");
    assertThat(normalized.getHeader().getSourceSystem()).isEqualTo("WEAVER_ECOLOGY");
  }

  private Map<String, Object> mockPayload() {
    Map<String, Object> main = new LinkedHashMap<>();
    main.put("processCode", "FI-SC-006");
    main.put("processName", "标准品批量品报价流程");
    main.put("applyDateTime", "2026-03-27 08:30:00");
    main.put("customer", "客户A");
    main.put("applicantUnit", "商用制冷");
    main.put("sourceCompany", "三花商用");
    main.put("sourceBusinessDivision", "商用事业部");
    main.put("applicantDept", "欧洲业务管理部");
    main.put("applicantOffice", "欧洲业务一处");
    main.put("exchangeRate", "7.12");
    main.put("weaverExtraHeader", "保留字段");

    Map<String, Object> item = new LinkedHashMap<>();
    item.put("seq", "1");
    item.put("materialNo", "1001");
    item.put("sunlModel", "SHF-A");
    item.put("businessType", "批量品");
    item.put("annualVolume", "12000");
    item.put("weaverExtraItem", "蓝色");

    Map<String, Object> fee = new LinkedHashMap<>();
    fee.put("feeCode", "moldFee");
    fee.put("feeName", "模具费");
    fee.put("feeCategory", "MOLD");
    fee.put("amount", "1200");

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("requestId", "WR-001");
    payload.put("formNo", "OA-W-001");
    payload.put("main", main);
    payload.put("items", List.of(item));
    payload.put("extraFees", List.of(fee));
    return payload;
  }
}
