package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.DrawingVerificationResult;
import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationPayload;
import com.sanhua.marketingcost.mapper.DrawingVerificationResultMapper;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 证据追加与任务指针原子提交；外部调用和 BOM 消费不进入此事务。 */
@Repository
public class DrawingVerificationStore {
  public static final String INITIAL = "INITIAL";
  public static final String MANUAL_CONFIRMATION = "MANUAL_CONFIRMATION";
  private final DrawingVerificationTaskMapper tasks;
  private final DrawingVerificationResultMapper results;

  public DrawingVerificationStore(
      DrawingVerificationTaskMapper tasks, DrawingVerificationResultMapper results) {
    this.tasks = tasks;
    this.results = results;
  }

  @Transactional
  public DrawingVerificationResult append(
      DrawingVerificationTask expected,
      String eventType,
      DrawingVerificationPayload payload,
      Long plmSourceResultId,
      String nextState,
      LocalDateTime now) {
    if (expected.getExternalTaskId() != null
        && !expected.getExternalTaskId().equals(payload.summary().taskId())) {
      throw new IllegalArgumentException("解析任务号与已绑定任务不一致");
    }
    if (MANUAL_CONFIRMATION.equals(eventType)) {
      DrawingVerificationResult source = results.findById(plmSourceResultId);
      if (source == null
          || !INITIAL.equals(source.getEventType())
          || !Objects.equals(source.getVerificationTaskId(), expected.getId())) {
        throw new IllegalArgumentException("人工确认必须关联同任务的首次 PLM 来源");
      }
    } else if (!INITIAL.equals(eventType) || plmSourceResultId != null) {
      throw new IllegalArgumentException("核验结果事件和 PLM 来源引用不一致");
    }
    var result = new DrawingVerificationResult();
    result.setVerificationTaskId(expected.getId());
    result.setEventType(eventType);
    result.setUpstreamRevision(payload.summary().revision());
    result.setRawSummaryJson(payload.rawJson());
    result.setDataJson(payload.dataJson());
    result.setPlmDataJson(payload.plmDataJson());
    result.setPlmSourceResultId(plmSourceResultId);
    result.setContentFingerprint(payload.contentFingerprint());
    result.setConfirmedAtMs(payload.summary().confirmedAt());
    result.setReceivedAt(now);
    results.insert(result);
    String consumption = "RECEIVED".equals(nextState) ? "PENDING" : "NOT_READY";
    if (tasks.attachResult(
            expected.getId(),
            expected.getRowVersion(),
            payload.summary().taskId(),
            result.getId(),
            payload.summary().editUrl(),
            payload.summary().differenceCount(),
            nextState,
            consumption,
            now)
        != 1) {
      throw new OptimisticLockingFailureException("取图任务已变化，请读取已保存结果后继续");
    }
    return result;
  }
}
