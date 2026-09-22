package com.sanhua.marketingcost.integration.oa;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration/oa/v1")
public class OaIntegrationController {
  private final OaInboxService inbox;
  private final OaIntegrationProperties properties;

  public OaIntegrationController(OaInboxService inbox, OaIntegrationProperties properties) {
    this.inbox = inbox; this.properties = properties;
  }

  @PostMapping(value = "/workflow-events", consumes = "application/json")
  public ResponseEntity<OaInboxService.Receipt> event(@AuthenticationPrincipal OaPeer peer,
      HttpServletRequest request) throws IOException {
    return ResponseEntity.accepted().body(inbox.receive(peer, OaMessageCodec.InterfaceType.WORKFLOW_EVENT, readBody(request)));
  }

  @GetMapping("/receipts/{requestId}")
  public OaInboxService.Receipt receipt(@AuthenticationPrincipal OaPeer peer, @PathVariable String requestId) {
    return inbox.query(peer, requestId);
  }

  @ExceptionHandler(OaIntegrationException.class)
  public ResponseEntity<Map<String, Object>> invalid(OaIntegrationException ex) {
    return ResponseEntity.status(ex.httpStatus()).body(Map.of("received", false, "stage", ex.stage(),
        "code", ex.code(), "message", ex.getMessage()));
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<Map<String, Object>> unavailable(DataAccessException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("received", false,
        "code", "RECEIPT_UNAVAILABLE", "message", "接收记录暂不可用，请使用原 requestId 查询或重试"));
  }

  private String readBody(HttpServletRequest request) throws IOException {
    int limit = properties.getMaxBodyBytes();
    if (request.getContentLengthLong() > limit) throw tooLarge();
    byte[] bytes = request.getInputStream().readNBytes(limit + 1);
    if (bytes.length > limit) throw tooLarge();
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException ex) {
      throw new OaIntegrationException(HttpStatus.BAD_REQUEST, "RECEIVE", "INVALID_ENCODING", "正文须为 UTF-8 编码");
    }
  }

  private OaIntegrationException tooLarge() {
    return new OaIntegrationException(HttpStatus.PAYLOAD_TOO_LARGE, "RECEIVE", "PAYLOAD_TOO_LARGE", "请求正文超过接收大小上限");
  }
}
