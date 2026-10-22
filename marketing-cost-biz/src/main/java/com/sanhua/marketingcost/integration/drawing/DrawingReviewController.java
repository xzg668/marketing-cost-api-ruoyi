package com.sanhua.marketingcost.integration.drawing;

import com.sanhua.marketingcost.integration.logging.ExternalHttpLoggingFilter;
import com.sanhua.marketingcost.service.electronicdrawing.DrawingReviewRejected;
import com.sanhua.marketingcost.service.electronicdrawing.DrawingReviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 解析服务的直接摘要入口；不使用报价员会话或通用业务信封。 */
@RestController
public class DrawingReviewController {
  public static final String PATH = "/integration/v1/drawings/review-results";
  private final DrawingVerificationCodec codec;
  private final DrawingReviewService reviews;

  public DrawingReviewController(DrawingVerificationCodec codec, DrawingReviewService reviews) {
    this.codec = codec; this.reviews = reviews;
  }

  @PostMapping(value = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> receive(@RequestBody(required = false) String body, HttpServletRequest request) {
    ExternalHttpLoggingFilter.exchange(request).ifPresent(log -> log.correlate("system", "DRAWING_PDF"));
    DrawingVerificationPayload payload;
    try {
      payload = codec.parse(body);
      requireHeader(request, "X-Review-Task", payload.summary().taskId());
      requireHeader(request, "Idempotency-Key", payload.summary().taskId());
    } catch (IllegalArgumentException invalid) {
      return reject(request, 400, "INVALID_REQUEST", invalid.getMessage());
    }
    ExternalHttpLoggingFilter.exchange(request).ifPresent(log -> {
      log.correlate("externalTaskId", payload.summary().taskId());
      log.correlate("differenceCount", payload.summary().differenceCount());
      log.correlate("revision", payload.summary().revision());
      log.correlate("itemCount", payload.summary().data().values().stream().mapToInt(drawing -> drawing.params().items().size()).sum());
    });
    try {
      var receipt = reviews.receive(payload);
      ExternalHttpLoggingFilter.exchange(request).ifPresent(log -> {
        log.result(receipt.duplicate() ? "DUPLICATE" : "RECEIVED");
        log.correlate("localTaskId", receipt.localTaskId());
        log.correlate("resultId", receipt.resultId());
        log.correlate("oaNo", receipt.oaNo());
        log.correlate("oaFormItemId", receipt.oaFormItemId());
        log.correlate("accountingMonth", receipt.accountingMonth());
        log.correlate("drawingNo", receipt.drawingNo());
        log.correlate("taskState", receipt.taskState());
        log.correlate("consumptionState", receipt.consumptionState());
      });
      return ResponseEntity.ok(new Acknowledgement(receipt.taskId(), receipt.resultId(), receipt.duplicate()));
    } catch (DrawingReviewRejected rejected) {
      int status = switch (rejected.reason()) {
        case INVALID_CONFIRMATION -> 400;
        case UNKNOWN_TASK -> 404;
        case CONTENT_CONFLICT, BINDING_CONFLICT -> 409;
      };
      return reject(request, status, rejected.reason().name(), rejected.getMessage());
    } catch (RuntimeException failed) {
      ExternalHttpLoggingFilter.exchange(request).ifPresent(log -> log.failure(failed));
      return reject(request, 503, "STORAGE_FAILED", "人工确认保存失败，请按原任务重试");
    }
  }

  private void requireHeader(HttpServletRequest request, String name, String expected) {
    var values = Collections.list(request.getHeaders(name));
    if (values.size() != 1 || !expected.equals(values.getFirst())) {
      throw new IllegalArgumentException(name + " 必须唯一且与正文 taskId 完全一致");
    }
  }

  private ResponseEntity<Rejection> reject(HttpServletRequest request, int status, String code, String message) {
    ExternalHttpLoggingFilter.exchange(request).ifPresent(log -> log.result(code));
    return ResponseEntity.status(status).body(new Rejection(code, message));
  }

  public record Acknowledgement(String taskId, Long resultId, boolean duplicate) {}
  public record Rejection(String code, String message) {}
}
