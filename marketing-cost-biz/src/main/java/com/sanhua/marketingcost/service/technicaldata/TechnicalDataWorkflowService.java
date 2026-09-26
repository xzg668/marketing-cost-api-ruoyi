package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository.Flow;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 查询人员办理及资料确认状态；退回由整单 I05 业务服务负责。 */
@Service
public class TechnicalDataWorkflowService {
  public record Status(List<Person> participants, boolean financeReady, boolean financeConfirmed,
      boolean canFinanceReview, String approvalFingerprint, List<String> editableModules,
      List<String> assignedModules, boolean canAdminister, boolean canViewSupplementOverview,
      List<TechnicalDataDependencies.Issue> dependencyIssues) {}
  public record Person(long recipientId, long assigneeUserId, String assigneeName, List<String> moduleTypes,
      String departmentName, String leaderName, String status, int round, Long submissionId,
      String returnReason, boolean canSubmit) {}

  private final QuoteTechTaskMapper tasks;
  private final QuoteTechModuleMapper modules;
  private final QuoteTechSubmissionMapper submissions;
  private final TechnicalDataOaRecipientRepository recipients;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final OaMessageCodec codec;
  private final TechnicalDataDependencies dependencies;
  private final OaWorkflowAccessPolicy access;

  public TechnicalDataWorkflowService(QuoteTechTaskMapper tasks, QuoteTechModuleMapper modules,
      QuoteTechSubmissionMapper submissions, TechnicalDataOaRecipientRepository recipients,
      TechnicalDataOaWorkflowRepository workflow, OaMessageCodec codec,
      TechnicalDataDependencies dependencies, OaWorkflowAccessPolicy access) {
    this.tasks=tasks; this.modules=modules; this.submissions=submissions; this.recipients=recipients;
    this.workflow=workflow; this.codec=codec; this.dependencies=dependencies; this.access=access;
  }

  @Transactional(readOnly = true)
  public Status status(long taskId, TechnicalDataActor actor) {
    var task = tasks.selectById(taskId);
    if (actor == null || !actor.canReadTask(task, modules.selectByTaskId(taskId))) throw forbidden("无权读取此产品流程");
    var flow = task.getOaFlowId() == null ? null : workflow.findFlow(task.getOaFlowId());
    boolean overview=actor.canViewSupplementOverview();
    var people = recipients.current(taskId).stream()
        .filter(person -> overview || Objects.equals(person.userId(),actor.userId()))
        .map(person -> {
          var snapshot=person.latestSubmissionId()==null?null:submissions.selectById(person.latestSubmissionId());
          boolean submitted=snapshot!=null && snapshot.getSentAt()!=null;
          String state=overview && !submitted?"OPEN":person.todoStatus();
          return new Person(person.id(),person.userId(),person.name(),person.modules(),person.departmentName(),
              person.leaderName(),state,overview && !submitted?0:person.submissionRound(),
              overview && !submitted?null:person.latestSubmissionId(),overview && !submitted?null:person.returnReason(),
              !overview && actor.canEdit() && "OPEN".equals(state) && "PUBLISHED".equals(task.getExternalTaskStatus()));
        }).toList();
    String basis = flow == null ? null : fingerprint(flow.id());
    boolean ready = flow != null && flow.financeReady();
    return new Status(people, ready, ready && Objects.equals(basis, flow.confirmedFingerprint()),
        canFinance(actor, flow), basis, modules.selectByTaskId(taskId).stream()
            .filter(module -> actor.canEditModule(task, module)).map(module -> module.getModuleType()).toList(),
        actor.assignedModules(task, modules.selectByTaskId(taskId)), actor.admin(), actor.canViewSupplementOverview(),
        dependencies.approvedIssues(modules.selectByTaskId(taskId)));
  }

  private boolean canFinance(TechnicalDataActor actor, Flow flow) {
    if (actor==null || actor.shortSession() || flow==null || !actor.canViewSupplementOverview()) return false;
    var view=access.view(flow.oaFormId());
    return view!=null && view.canCost();
  }
  private String fingerprint(long flowId) { return codec.canonicalHash(workflow.approvalBasis(flowId)); }
  private TechnicalDataTaskException forbidden(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN,message);
  }
}
