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
  private final MonthlyRepriceCheckService checks;
  private final TransactionTemplate transaction;
  public MonthlyRepriceDispatchService(MonthlyRepriceContractMapper contracts,MonthlyRepriceWorkflowMapper workflow,
      MonthlyRepriceContractService transitions,MonthlyRepriceCheckService checks,PlatformTransactionManager manager) {
    this.contracts=contracts;this.workflow=workflow;this.transitions=transitions;this.checks=checks;
    transaction=new TransactionTemplate(manager);transaction.setTimeout(120);
  }
  public CostRunTask claim(String worker) {
    return transaction.execute(tx->{
      var batch=workflow.leaseBatch();
      if(batch==null) return null;
      var task=workflow.candidate(batch.getRepriceNo(),!"FAILED".equals(batch.getStatus()));
      if(task==null || workflow.lease(task.getId(),worker)!=1) return null;
      if(!"FAILED".equals(batch.getStatus())) contracts.state(batch.getRepriceNo(),"RUNNING");
      return contracts.readTask(batch.getRepriceNo(),task.getId());
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
          || !worker.equals(current.getWorkerId()) || !current.getExecutionNo().equals(task.getExecutionNo())) return;
      String message=failure.getMessage();if(message==null || message.isBlank()) message="调价执行失败，请检查资料后重试";
      transitions.fail(task.getSourceNo(),task.getId(),task.getExecutionNo(),message.substring(0,Math.min(1000,message.length())));
    });
  }
  public void checkCompleted() {
    for(var candidate:workflow.checking()) transaction.executeWithoutResult(tx->{
      var batch=contracts.lock(candidate.getRepriceNo());
      if(batch==null || !"CHECKING".equals(batch.getStatus())) return;
      var report=checks.verify(batch);
      transitions.recordCheck(batch.getRepriceNo(),batch.getResultRevision(),report.passed(),report.evidence());
    });
  }
}
