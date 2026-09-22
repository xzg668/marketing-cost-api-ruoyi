package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechSubmission;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 个人提交的本地准备事务。传输、OA 确认和审批不属于此服务。 */
@Service
public class TechnicalDataSubmissionSnapshotService {
  private final QuoteTechTaskMapper taskMapper;
  private final QuoteTechSubmissionMapper submissionMapper;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataParticipantVersions versions;
  private final TechnicalDataOaRecipientRepository recipients;
  private final QuoteTechModuleMapper moduleMapper;
  private final OaMessageCodec messageCodec;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataSubmissionSummary summary;

  public TechnicalDataSubmissionSnapshotService(
      QuoteTechTaskMapper taskMapper, QuoteTechSubmissionMapper submissionMapper,
      QuoteTechnicalDataRepository repository, TechnicalDataParticipantVersions versions,
      TechnicalDataVersionContentCodec codec, TechnicalDataSubmissionSummary summary,
      TechnicalDataOaRecipientRepository recipients, QuoteTechModuleMapper moduleMapper, OaMessageCodec messageCodec) {
    this.taskMapper = taskMapper;
    this.submissionMapper = submissionMapper;
    this.repository = repository;
    this.versions = versions;
    this.recipients = recipients;
    this.moduleMapper = moduleMapper;
    this.messageCodec = messageCodec;
    this.codec = codec;
    this.summary = summary;
  }

  @Transactional
  public QuoteTechSubmission prepare(
      Long taskId, String requestId, int expectedTaskVersion, int expectedProductVersion, Recipient person,
      TechnicalDataActor actor) {
    if (taskId == null || taskId <= 0 || requestId == null || requestId.isBlank()
        || requestId.length() > 128 || expectedTaskVersion < 0 || expectedProductVersion < 0) {
      throw error(TechnicalDataTaskErrorCode.INVALID_REQUEST, "提交身份或并发版本无效");
    }
    QuoteTechTask task = taskMapper.selectByIdForUpdate(taskId);
    requireAccess(task, actor, person);
    QuoteTechSubmission existing = submissionMapper.selectByRequest(taskId, requestId);
    if (existing != null) {
      if (!Objects.equals(existing.getExpectedTaskVersion(), expectedTaskVersion)
          || !Objects.equals(existing.getExpectedProductVersion(), expectedProductVersion)
          || !Objects.equals(existing.getAssigneeUserId(), person.userId())) {
        throw error(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "同一提交请求不能更换版本");
      }
      return existing;
    }
    if (!Objects.equals(task.getTaskVersion(), expectedTaskVersion)
        || !"OPEN".equals(person.todoStatus())) {
      throw error(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "任务已变化或当前不能准备提交");
    }
    var products = repository.lockActiveProducts(taskId);
    if (products.size() != 1 || !Objects.equals(products.get(0).getContentSchemaVersion(), 2)
        || !Objects.equals(products.get(0).getOaFormItemId(), task.getOaFormItemId())) {
      throw error(TechnicalDataTaskErrorCode.INVALID_REQUEST, "个人提交仅接受一个活动产品的九模块任务");
    }
    var product = products.get(0);
    var frozen = versions.freeze(product, person, expectedProductVersion, actor.userId());
    var modules = codec.readReferenceSnapshot(frozen.getReferenceSnapshotJson());
    var packages = repository.findPackageItems(frozen.getId());
    var auxiliaries = repository.findAuxItems(frozen.getId());
    var salaries = repository.findSalaryItems(frozen.getId());
    QuoteTechSubmission submission = new QuoteTechSubmission();
    submission.setTaskId(taskId);
    submission.setRecipientId(person.id());
    submission.setModuleTypesJson(messageCodec.write(person.modules()));
    submission.setLeaderExternalId(person.leaderExternalId());
    submission.setProductId(product.getId());
    submission.setTechnicalVersionId(frozen.getId());
    submission.setSubmissionRound(submissionMapper.nextRound(taskId, person.userId()));
    submission.setRequestId(requestId);
    submission.setExpectedTaskVersion(expectedTaskVersion);
    submission.setExpectedProductVersion(expectedProductVersion);
    submission.setContentSchemaVersion(codec.schemaVersion(frozen));
    submission.setContentFingerprint(frozen.getContentFingerprint());
    submission.setContentSnapshotJson(codec.versionContentJson(frozen, modules, packages, auxiliaries, salaries));
    submission.setPreviousSubmissionId(submissionMapper.selectLastSentId(taskId, person.userId()));
    var previous = submission.getPreviousSubmissionId() == null ? null
        : submissionMapper.selectById(submission.getPreviousSubmissionId());
    submission.setSummaryJson(summary.summarize(submission.getContentSnapshotJson(),
        previous == null ? null : previous.getContentSnapshotJson()));
    submission.setAssigneeUserId(person.userId());
    submission.setSubmittedBy(actor.userId());
    submission.setPreparedAt(LocalDateTime.now());
    submission.setSubmissionStatus("PREPARED");
    submission.setRowVersion(0);
    submissionMapper.insert(submission);
    recipients.prepared(person.id(), submission.getId(), submission.getSubmissionRound());
    recipients.refreshTask(taskId);
    return submission;
  }

  private void requireAccess(QuoteTechTask task, TechnicalDataActor actor, Recipient person) {
    if (task == null || actor == null || !actor.canEdit() || !actor.canAccessTask(task.getId())
        || !Objects.equals(task.getActiveFlag(), 1)
        || person == null || !person.active() || person.taskId() != task.getId()
        || (!actor.admin() && !Objects.equals(person.userId(), actor.userId()))) {
      throw error(TechnicalDataTaskErrorCode.FORBIDDEN, "无权办理该产品任务");
    }
  }

  @Transactional(readOnly = true)
  public QuoteTechSubmission read(Long taskId, Long submissionId, TechnicalDataActor actor) {
    var task = taskMapper.selectById(taskId);
    var modules = moduleMapper.selectByTaskId(taskId);
    if (actor == null || !actor.canReadTask(task, modules)) {
      throw error(TechnicalDataTaskErrorCode.FORBIDDEN, "无权读取该产品提交快照");
    }
    QuoteTechSubmission result = submissionMapper.selectById(submissionId);
    if (result == null || !Objects.equals(result.getTaskId(), taskId)) {
      throw error(TechnicalDataTaskErrorCode.FORBIDDEN, "不能跨产品读取提交快照");
    }
    return result;
  }

  private TechnicalDataTaskException error(TechnicalDataTaskErrorCode code, String message) {
    return new TechnicalDataTaskException(code, message);
  }

}
