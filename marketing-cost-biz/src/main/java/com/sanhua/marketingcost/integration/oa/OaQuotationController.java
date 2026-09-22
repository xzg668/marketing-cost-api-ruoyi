package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/open-api/v1/oa/quotation-requests")
public class OaQuotationController {
  private final OaQuotationService service;
  private final OaIntegrationProperties properties;

  public OaQuotationController(OaQuotationService service, OaIntegrationProperties properties) {
    this.service = service;
    this.properties = properties;
  }

  @PostMapping(consumes = "application/json", produces = "application/json")
  public JsonNode receive(@AuthenticationPrincipal OaPeer peer, HttpServletRequest request)
      throws IOException {
    int limit = properties.getMaxBodyBytes();
    if (request.getContentLengthLong() > limit) throw tooLarge();
    byte[] bytes = request.getInputStream().readNBytes(limit + 1);
    if (bytes.length > limit) throw tooLarge();
    try {
      String raw =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      return service.receive(peer, raw);
    } catch (java.nio.charset.CharacterCodingException ex) {
      throw new OaQuotationValidationException("$", "请求正文须为UTF-8编码");
    }
  }

  @ExceptionHandler(OaQuotationValidationException.class)
  ResponseEntity<OaQuotationResponse> invalid(OaQuotationValidationException ex) {
    return ResponseEntity.badRequest()
        .body(OaQuotationResponse.rejected("VALIDATION_FAILED", "报价需求字段校验不通过", ex.errors()));
  }

  @ExceptionHandler(OaIntegrationException.class)
  ResponseEntity<OaQuotationResponse> conflict(OaIntegrationException ex) {
    return ResponseEntity.status(ex.httpStatus())
        .body(OaQuotationResponse.rejected(ex.code(), ex.getMessage(), null));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<OaQuotationResponse> storage(DataAccessException ex) {
    if (ex.getMostSpecificCause() instanceof java.sql.SQLException sql) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "I01 save rolled back: SQLState={}, databaseCode={}", sql.getSQLState(), sql.getErrorCode());
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(OaQuotationResponse.failed("STORAGE_UNAVAILABLE", "本次保存未完成，请使用原requestId和原内容重试"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<OaQuotationResponse> unexpected(Exception ex) {
    // 业务事务已经回滚；对外不暴露 SQL、配置或原始请求正文。
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("I01 quotation reception failed: {}", ex.getClass().getSimpleName());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(OaQuotationResponse.failed("INTERNAL_ERROR", "本次保存未完成，请使用原requestId和原内容重试"));
  }

  private OaIntegrationException tooLarge() {
    return new OaIntegrationException(
        HttpStatus.PAYLOAD_TOO_LARGE, "RECEIVE", "PAYLOAD_TOO_LARGE", "请求正文超过接收大小上限");
  }
}
