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
@RequestMapping("/integration/v1/workflow-events")
public class OaWorkflowNotificationController {
  private final OaWorkflowNotificationService service;
  private final OaIntegrationProperties properties;

  public OaWorkflowNotificationController(OaWorkflowNotificationService service, OaIntegrationProperties properties) {
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
  ResponseEntity<OaWorkflowReply> invalid(OaQuotationValidationException ex) {
    return ResponseEntity.badRequest()
        .body(OaWorkflowReply.rejected("VALIDATION_FAILED", "通知JSON格式无效"));
  }

  @ExceptionHandler(OaIntegrationException.class)
  ResponseEntity<OaWorkflowReply> conflict(OaIntegrationException ex) {
    return ResponseEntity.status(ex.httpStatus())
        .body(OaWorkflowReply.rejected(ex.code(), ex.getMessage()));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<OaWorkflowReply> storage(DataAccessException ex) {
    if (ex.getMostSpecificCause() instanceof java.sql.SQLException sql) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "I04/I08 save rolled back: SQLState={}, databaseCode={}", sql.getSQLState(), sql.getErrorCode());
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new OaWorkflowReply("STORAGE_UNAVAILABLE", "通知处理暂不可用，请使用原requestId和原内容重试", new OaWorkflowReply.Data("FAILED")));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<OaWorkflowReply> unexpected(Exception ex) {
    // 业务事务已经回滚；对外不暴露 SQL、配置或原始请求正文。
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("I04/I08 quotation reception failed: {}", ex.getClass().getSimpleName());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new OaWorkflowReply("INTERNAL_ERROR", "通知处理异常，请使用原requestId和原内容重试", new OaWorkflowReply.Data("FAILED")));
  }

  private OaIntegrationException tooLarge() {
    return new OaIntegrationException(
        HttpStatus.PAYLOAD_TOO_LARGE, "RECEIVE", "PAYLOAD_TOO_LARGE", "请求正文超过接收大小上限");
  }
}
