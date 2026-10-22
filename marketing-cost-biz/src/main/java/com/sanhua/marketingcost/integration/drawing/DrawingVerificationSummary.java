package com.sanhua.marketingcost.integration.drawing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PDF 服务 v2 摘要。图纸数量、重量和序号始终保留原文。 */
public record DrawingVerificationSummary(
    String taskId,
    String status,
    int differenceCount,
    Map<String, Drawing> data,
    @JsonProperty("plm_data") JsonNode plmData,
    String verificationStatus,
    String editUrl,
    List<String> reviewReasons,
    Long expiresAt,
    int revision,
    Long confirmedAt,
    String deliveryStatus,
    int deliveryAttempts,
    String engine,
    JsonNode drafts,
    String draftEngine,
    int schemaVersion) {

  public DrawingVerificationSummary {
    data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    plmData = plmData.deepCopy();
    reviewReasons = List.copyOf(reviewReasons);
    drafts = drafts.deepCopy();
  }

  @Override public JsonNode plmData() { return plmData.deepCopy(); }
  @Override public JsonNode drafts() { return drafts.deepCopy(); }

  public record Drawing(String company, Parameters params) {}
  public record Parameters(List<Item> items) {
    public Parameters { items = List.copyOf(items); }
  }
  public record Item(String no, String code, String name, String qty, String mat,
                     String grade, String hsf, String weight, String note) {}
}
