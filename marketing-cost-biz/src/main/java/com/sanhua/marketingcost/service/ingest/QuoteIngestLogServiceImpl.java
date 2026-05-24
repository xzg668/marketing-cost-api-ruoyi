package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedDocument;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedHeader;
import com.sanhua.marketingcost.entity.QuoteIngestLog;
import com.sanhua.marketingcost.enums.QuoteIngestStatus;
import com.sanhua.marketingcost.mapper.QuoteIngestLogMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuoteIngestLogServiceImpl implements QuoteIngestLogService {
  private final QuoteIngestLogMapper quoteIngestLogMapper;
  private final ObjectMapper objectMapper;

  public QuoteIngestLogServiceImpl(
      QuoteIngestLogMapper quoteIngestLogMapper, ObjectMapper objectMapper) {
    this.quoteIngestLogMapper = quoteIngestLogMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public QuoteIngestLog findByIdempotencyKey(String idempotencyKey) {
    if (!StringUtils.hasText(idempotencyKey)) {
      return null;
    }
    return quoteIngestLogMapper.selectOne(
        Wrappers.lambdaQuery(QuoteIngestLog.class)
            .eq(QuoteIngestLog::getIdempotencyKey, idempotencyKey.trim()));
  }

  @Override
  public QuoteIngestLog createReceived(
      QuoteIngestRequest request,
      QuoteNormalizedDocument normalized,
      String requestId,
      String idempotencyKey,
      String payloadHash,
      String payloadJson,
      String normalizedJson) {
    QuoteNormalizedHeader header = normalized == null ? null : normalized.getHeader();
    QuoteIngestLog log = new QuoteIngestLog();
    log.setRequestId(requestId);
    log.setIdempotencyKey(idempotencyKey);
    log.setPayloadHash(payloadHash);
    log.setSourceType(header == null ? requestSourceType(request) : header.getSourceType());
    log.setSourceSystem(header == null ? requestSourceSystem(request) : header.getSourceSystem());
    log.setExternalFormNo(request == null ? null : trimToNull(request.getExternalFormNo()));
    log.setOaNo(header == null ? null : header.getOaNo());
    log.setProcessCode(header == null ? null : header.getProcessCode());
    log.setProcessName(header == null ? null : header.getProcessName());
    log.setQuoteScenario(header == null ? null : header.getQuoteScenario());
    log.setIngestStatus(QuoteIngestStatus.RECEIVED.getCode());
    log.setClassificationStatus(header == null ? null : header.getClassificationStatus());
    log.setPayloadJson(payloadJson);
    log.setNormalizedJson(normalizedJson);
    log.setValidationErrors(toJson(normalized == null ? null : normalized.getErrors()));
    log.setWarningMessages(toJson(normalized == null ? null : normalized.getWarnings()));
    log.setReceivedAt(LocalDateTime.now());
    log.setCreatedAt(LocalDateTime.now());
    log.setUpdatedAt(LocalDateTime.now());
    quoteIngestLogMapper.insert(log);
    return log;
  }

  @Override
  public void refreshReceived(
      QuoteIngestLog log,
      QuoteIngestRequest request,
      QuoteNormalizedDocument normalized,
      String requestId,
      String payloadHash,
      String payloadJson,
      String normalizedJson) {
    if (log == null) {
      return;
    }
    QuoteNormalizedHeader header = normalized == null ? null : normalized.getHeader();
    log.setRequestId(requestId);
    log.setPayloadHash(payloadHash);
    log.setSourceType(header == null ? requestSourceType(request) : header.getSourceType());
    log.setSourceSystem(header == null ? requestSourceSystem(request) : header.getSourceSystem());
    log.setExternalFormNo(request == null ? null : trimToNull(request.getExternalFormNo()));
    log.setOaNo(header == null ? null : header.getOaNo());
    log.setProcessCode(header == null ? null : header.getProcessCode());
    log.setProcessName(header == null ? null : header.getProcessName());
    log.setQuoteScenario(header == null ? null : header.getQuoteScenario());
    log.setIngestStatus(QuoteIngestStatus.RECEIVED.getCode());
    log.setClassificationStatus(header == null ? null : header.getClassificationStatus());
    log.setPayloadJson(payloadJson);
    log.setNormalizedJson(normalizedJson);
    log.setValidationErrors(toJson(normalized == null ? null : normalized.getErrors()));
    log.setWarningMessages(toJson(normalized == null ? null : normalized.getWarnings()));
    log.setErrorMessage(null);
    log.setReceivedAt(LocalDateTime.now());
    log.setProcessedAt(null);
    log.setUpdatedAt(LocalDateTime.now());
    quoteIngestLogMapper.updateById(log);
  }

  @Override
  public void markRejected(QuoteIngestLog log, QuoteNormalizedDocument normalized, String message) {
    if (log == null) {
      return;
    }
    log.setIngestStatus(QuoteIngestStatus.REJECTED.getCode());
    log.setValidationErrors(toJson(normalized == null ? null : normalized.getErrors()));
    log.setWarningMessages(toJson(normalized == null ? null : normalized.getWarnings()));
    log.setErrorMessage(truncate(message, 1000));
    log.setProcessedAt(LocalDateTime.now());
    log.setUpdatedAt(LocalDateTime.now());
    quoteIngestLogMapper.updateById(log);
  }

  @Override
  public void markImported(
      QuoteIngestLog log, QuoteNormalizedDocument normalized, Long oaFormId, String oaNo) {
    if (log == null) {
      return;
    }
    QuoteNormalizedHeader header = normalized == null ? null : normalized.getHeader();
    log.setOaNo(oaNo);
    log.setQuoteScenario(header == null ? null : header.getQuoteScenario());
    log.setClassificationStatus(header == null ? null : header.getClassificationStatus());
    log.setIngestStatus(
        "PENDING".equals(log.getClassificationStatus())
            ? QuoteIngestStatus.CLASSIFY_PENDING.getCode()
            : QuoteIngestStatus.IMPORTED.getCode());
    log.setValidationErrors(toJson(normalized == null ? null : normalized.getErrors()));
    log.setWarningMessages(toJson(normalized == null ? null : normalized.getWarnings()));
    log.setErrorMessage(null);
    log.setProcessedAt(LocalDateTime.now());
    log.setUpdatedAt(LocalDateTime.now());
    quoteIngestLogMapper.updateById(log);
  }

  private String toJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      return "[]";
    }
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private String requestSourceType(QuoteIngestRequest request) {
    return request == null ? null : trimToNull(request.getSourceType());
  }

  private String requestSourceSystem(QuoteIngestRequest request) {
    return request == null ? null : trimToNull(request.getSourceSystem());
  }
}
