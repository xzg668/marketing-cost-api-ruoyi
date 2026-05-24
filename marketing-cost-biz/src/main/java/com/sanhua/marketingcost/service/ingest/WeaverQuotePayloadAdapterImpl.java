package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFieldRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.enums.QuoteSourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WeaverQuotePayloadAdapterImpl implements WeaverQuotePayloadAdapter {
  private static final String SOURCE_SYSTEM = "WEAVER_ECOLOGY";
  private static final String DEFAULT_VERSION = "1";

  @Override
  public QuoteIngestRequest adapt(Map<String, Object> rawPayload) {
    Map<String, Object> payload = rawPayload == null ? new LinkedHashMap<>() : rawPayload;
    QuoteIngestRequest request = new QuoteIngestRequest();
    request.setSourceType(QuoteSourceType.WEAVER_OA.getCode());
    request.setSourceSystem(SOURCE_SYSTEM);
    request.setVersion(value(payload, "version", DEFAULT_VERSION));
    request.setRequestId(value(payload, "requestId", null));
    request.setHeader(new QuoteIngestHeaderRequest());
    request.setRawPayload(payload);

    // 泛微真实结构未接入前，只约定主表和明细表字段编码与 Excel 隐藏映射 field_code 保持一致。
    Map<String, Object> main = firstMap(payload, "main", "fields", "mainData");
    bindKnownRootFields(request, payload);
    bindMainFields(request, main);
    bindItems(request, firstRows(payload, "items", "detailRows", "details", "detailData"));
    bindFees(request.getExtraFees(), payload.get("extraFees"), SOURCE_SYSTEM + ".extraFees");

    request.setSourceType(QuoteSourceType.WEAVER_OA.getCode());
    request.setSourceSystem(SOURCE_SYSTEM);
    if (!StringUtils.hasText(request.getOaNo())) {
      String formNo = value(payload, "oaNo", value(payload, "formNo", value(payload, "workflowRequestId", null)));
      request.setOaNo(formNo);
    }
    if (!StringUtils.hasText(request.getExternalFormNo())) {
      request.setExternalFormNo(
          value(payload, "externalFormNo", value(payload, "formNo", request.getOaNo())));
    }
    if (StringUtils.hasText(request.getExternalFormNo())) {
      request.setIdempotencyKey(
          QuoteSourceType.WEAVER_OA.getCode()
              + ":"
              + request.getExternalFormNo().trim()
              + ":"
              + request.getVersion());
    }
    return request;
  }

  private void bindKnownRootFields(QuoteIngestRequest request, Map<String, Object> payload) {
    for (Map.Entry<String, Object> entry : payload.entrySet()) {
      String fieldCode = entry.getKey();
      if (isContainerField(fieldCode)) {
        continue;
      }
      String value = scalarValue(entry.getValue());
      if (!StringUtils.hasText(value)) {
        continue;
      }
      QuoteIngestFieldBinder.applyRequestField(request, fieldCode, value);
      QuoteIngestFieldBinder.applyHeaderField(request.getHeader(), fieldCode, value);
    }
  }

  private void bindMainFields(QuoteIngestRequest request, Map<String, Object> main) {
    for (Map.Entry<String, Object> entry : main.entrySet()) {
      String fieldCode = entry.getKey();
      String value = scalarValue(entry.getValue());
      if (!StringUtils.hasText(fieldCode) || !StringUtils.hasText(value)) {
        continue;
      }
      if (!QuoteIngestFieldBinder.applyRequestField(request, fieldCode, value)
          && !QuoteIngestFieldBinder.applyHeaderField(request.getHeader(), fieldCode, value)) {
        request.getExtraFields().add(extraField(fieldCode, value, SOURCE_SYSTEM + ".main." + fieldCode));
      }
    }
  }

  private void bindItems(QuoteIngestRequest request, List<Map<String, Object>> rows) {
    for (int index = 0; index < rows.size(); index++) {
      Map<String, Object> row = rows.get(index);
      QuoteIngestItemRequest item = new QuoteIngestItemRequest();
      item.setExternalLineId(SOURCE_SYSTEM + ".items[" + index + "]");
      for (Map.Entry<String, Object> entry : row.entrySet()) {
        String fieldCode = entry.getKey();
        if ("extraFees".equals(fieldCode)) {
          bindFees(item.getExtraFees(), entry.getValue(), SOURCE_SYSTEM + ".items[" + index + "].extraFees");
          continue;
        }
        String value = scalarValue(entry.getValue());
        if (!StringUtils.hasText(fieldCode) || !StringUtils.hasText(value)) {
          continue;
        }
        if (!QuoteIngestFieldBinder.applyItemField(item, fieldCode, value)) {
          item.getExtraFields().add(extraField(fieldCode, value, SOURCE_SYSTEM + ".items[" + index + "]." + fieldCode));
        }
      }
      request.getItems().add(item);
    }
  }

  private void bindFees(List<QuoteExtraFeeRequest> target, Object value, String sourcePath) {
    List<Map<String, Object>> rows = rows(value);
    for (int index = 0; index < rows.size(); index++) {
      Map<String, Object> row = rows.get(index);
      QuoteExtraFeeRequest fee = new QuoteExtraFeeRequest();
      fee.setFeeCode(value(row, "feeCode", null));
      fee.setFeeName(value(row, "feeName", null));
      fee.setFeeCategory(value(row, "feeCategory", null));
      fee.setAmount(value(row, "amount", null));
      fee.setUnit(value(row, "unit", null));
      fee.setRemark(value(row, "remark", null));
      fee.setSourceFieldName(value(row, "sourceFieldName", fee.getFeeName()));
      fee.setSourceFieldPath(value(row, "sourceFieldPath", sourcePath + "[" + index + "]"));
      target.add(fee);
    }
  }

  private QuoteExtraFieldRequest extraField(String fieldCode, String value, String sourcePath) {
    QuoteExtraFieldRequest field = new QuoteExtraFieldRequest();
    field.setFieldCode(fieldCode);
    field.setFieldName(fieldCode);
    field.setFieldValue(value);
    field.setValueType(valueType(value));
    field.setSourceFieldName(fieldCode);
    field.setSourceFieldPath(sourcePath);
    return field;
  }

  private boolean isContainerField(String fieldCode) {
    return "main".equals(fieldCode)
        || "fields".equals(fieldCode)
        || "mainData".equals(fieldCode)
        || "items".equals(fieldCode)
        || "detailRows".equals(fieldCode)
        || "details".equals(fieldCode)
        || "detailData".equals(fieldCode)
        || "extraFees".equals(fieldCode)
        || "rawPayload".equals(fieldCode);
  }

  private Map<String, Object> firstMap(Map<String, Object> payload, String... keys) {
    for (String key : keys) {
      Map<String, Object> map = map(payload.get(key));
      if (!map.isEmpty()) {
        return map;
      }
    }
    return new LinkedHashMap<>();
  }

  private List<Map<String, Object>> firstRows(Map<String, Object> payload, String... keys) {
    for (String key : keys) {
      Object value = payload.get(key);
      List<Map<String, Object>> direct = rows(value);
      if (!direct.isEmpty()) {
        return direct;
      }
      Map<String, Object> grouped = map(value);
      for (Object child : grouped.values()) {
        List<Map<String, Object>> nested = rows(child);
        if (!nested.isEmpty()) {
          return nested;
        }
      }
    }
    return List.of();
  }

  private List<Map<String, Object>> rows(Object value) {
    if (!(value instanceof Iterable<?> iterable)) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object item : iterable) {
      Map<String, Object> row = map(item);
      if (!row.isEmpty()) {
        rows.add(row);
      }
    }
    return rows;
  }

  private Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      return new LinkedHashMap<>();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (entry.getKey() != null) {
        result.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    }
    return result;
  }

  private String value(Map<String, Object> source, String key, String fallback) {
    String value = scalarValue(source.get(key));
    return StringUtils.hasText(value) ? value : fallback;
  }

  private String scalarValue(Object value) {
    if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
      return null;
    }
    return String.valueOf(value).trim();
  }

  private String valueType(String value) {
    String normalized = StringUtils.hasText(value) ? value.trim() : null;
    if (normalized == null) {
      return "TEXT";
    }
    if (normalized.matches("-?\\d+(,\\d{3})*(\\.\\d+)?")) {
      return "NUMBER";
    }
    if (normalized.matches("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}.*")) {
      return "DATE";
    }
    return "TEXT";
  }
}
