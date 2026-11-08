package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceWorkflowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Small durable leasing/check transactions; never hold a whole batch transaction while costing. */
@Service
public class MonthlyRepriceDispatchService {
  private final MonthlyRepriceContractMapper contracts;
  private final MonthlyRepriceWorkflowMapper workflow;
  private final MonthlyRepriceContractService transitions;
  private final MonthlyRepriceCheckCoordinator checks;
  private final MonthlyRepriceAdmission admission;
  private final TransactionTemplate transaction;
  public MonthlyRepriceDispatchService(MonthlyRepriceContractMapper contracts,MonthlyRepriceWorkflowMapper workflow,
      MonthlyRepriceContractService transitions,MonthlyRepriceCheckCoordinator checks,PlatformTransactionManager manager,
      MonthlyRepriceAdmission admission) {
    this.contracts=contracts;this.workflow=workflow;this.transitions=transitions;this.checks=checks;
    transaction=new TransactionTemplate(manager);transaction.setTimeout(120);
    this.admission=admission;
  }
  public CostRunTask claim(String worker) {
    var tasks = claimBatch(worker, 1);
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  /** Only lease work that the caller can execute now; all claims commit or roll back together. */
  public java.util.List<CostRunTask> claimBatch(String worker, int limit) {
    if (worker == null || worker.isBlank() || worker.length() > 128 || limit < 1 || limit > 64)
      throw new IllegalArgumentException("调价领取须有效实例及1～64的数量上限");
    return admission.claim(limit, (allowed, policy) -> {
      var claimed = new java.util.ArrayList<CostRunTask>(allowed);
      for (int index = 0; index < allowed; index++) {
        var batch = workflow.leaseBatch();
        if (batch == null) break;
        var task = workflow.candidate(batch.getRepriceNo());
        if (task == null || workflow.lease(task.getId(), worker, policy) != 1) break;
        if (!"FAILED".equals(batch.getStatus())) contracts.state(batch.getRepriceNo(), "RUNNING");
        claimed.add(contracts.readTask(batch.getRepriceNo(), task.getId()));
      }
      return java.util.List.copyOf(claimed);
    });
  }
  public void heartbeat(CostRunTask task,String worker) {
    workflow.heartbeat(task.getId(),task.getExecutionNo(),worker);
  }
  public void fail(CostRunTask task,String worker,Exception failure) {
    transaction.executeWithoutResult(tx->{
      var batch=contracts.lock(task.getSourceNo());
      var current=contracts.readTask(task.getSourceNo(),task.getId());
      if(batch==null || current==null || !"RUNNING".equals(current.getStatus())
          || !worker.equals(current.getWorkerId()) || !current.getExecutionNo().equals(task.getExecutionNo())
          || !contracts.ownsLiveTask(task.getSourceNo(),task.getId(),task.getExecutionNo(),worker)) return;
      var decision=MonthlyRepriceFailures.decide(failure,current.getResultSummaryJson(),current.getMaxRetryCount());
      if(decision.retry()) {
        if(workflow.defer(task.getId(),task.getExecutionNo(),worker,decision.json(),
            decision.failure().message(),decision.delaySeconds())!=1)
          throw new IllegalStateException("调价任务重试登记时租约已失效");
        // Keep unfinished peers visible while other customers and checked-subset confirmation proceed.
        contracts.state(task.getSourceNo(),"FAILED".equals(batch.getStatus())?"FAILED":"RUNNING");
      } else {
        // Identical diagnostics may report zero changed rows. The final FAILED transition
        // below still requires the live owner/execution and rolls this entire transaction back.
        workflow.failureSummary(task.getId(),task.getExecutionNo(),worker,decision.json());
        transitions.fail(task.getSourceNo(),task.getId(),task.getExecutionNo(),worker,decision.failure().message());
      }
    });
  }
  public void checkCompleted() {
    for(var candidate:workflow.checking()) checks.check(candidate.getRepriceNo());
  }
}
