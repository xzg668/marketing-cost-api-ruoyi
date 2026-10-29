package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.config.MonthlyRepriceProperties;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceWorkflowMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized operator commands share the quotation publication mutex, including transaction commit. */
@Service
public class MonthlyRepriceWorkflowService {
  public record View(MonthlyRepriceBatch batch, boolean quotationBlocked, boolean canOperate,
      List<String> problems) {}
  private final MonthlyQuotationControl control;
  private final MonthlyRepriceContractService contracts;
  private final MonthlyRepriceContractMapper mapper;
  private final MonthlyRepriceWorkflowMapper workflow;
  private final MonthlyRepriceCheckService checks;
  private final MonthlyRepriceProperties properties;
  private final TransactionTemplate transaction;

  public MonthlyRepriceWorkflowService(MonthlyQuotationControl control,MonthlyRepriceContractService contracts,
      MonthlyRepriceContractMapper mapper,MonthlyRepriceWorkflowMapper workflow,MonthlyRepriceCheckService checks,
      MonthlyRepriceProperties properties,PlatformTransactionManager manager) {
    this.control=control;this.contracts=contracts;this.mapper=mapper;this.workflow=workflow;
    this.checks=checks;this.properties=properties;transaction=new TransactionTemplate(manager);
    transaction.setTimeout(120);
  }
  public View current() {
    String unit=unit();
    var batch=workflow.active(unit);
    if(batch==null) batch=workflow.month(unit,YearMonth.now().toString());
    return view(unit,batch);
  }
  public View start() {
    String unit=unit();MonthlyRepriceContractService.requireOperator(unit);
    if(!properties.isEnabled() || !properties.isBusinessUnitAllowed(unit))
      throw new IllegalStateException("当前业务单元尚未开放调价");
    return control.serialized(unit,()->view(unit,contracts.enqueue(unit,YearMonth.now())));
  }
  public View retry(String no) {
    String unit=unit();MonthlyRepriceContractService.requireOperator(unit);
    return control.serialized(unit,()->transaction.execute(tx->{
      requireBatch(no,unit);return view(unit,contracts.retry(no));
    }));
  }
  public View confirm(String no,long revision) {
    String unit=unit();MonthlyRepriceContractService.requireOperator(unit);
    return control.serialized(unit,()->{
      var confirmed=transaction.execute(tx->{
      var batch=requireBatch(no,unit);
      if(!"CONFIRMED".equals(batch.getStatus())) {
        if(!"WAIT_CONFIRM".equals(batch.getStatus()) || batch.getResultRevision()!=revision
            || !java.util.Objects.equals(batch.getCheckedRevision(),revision))
          throw new IllegalStateException("请刷新并确认当前检查通过的结果");
        var report=checks.verify(batch);
        if(!report.passed()) throw new IllegalStateException("确认检查失败："+String.join("；",report.problems()));
      }
      return contracts.confirm(no,revision);
      });
      // Read control only after the confirmation transaction has committed.
      return view(unit,confirmed);
    });
  }
  private MonthlyRepriceBatch requireBatch(String no,String unit) {
    var batch=mapper.lock(no);
    if(batch==null || !unit.equals(batch.getBusinessUnitType()))
      throw new org.springframework.security.access.AccessDeniedException("当前业务单元无权操作该调价批次");
    return batch;
  }
  private View view(String unit,MonthlyRepriceBatch batch) {
    boolean canOperate=MonthlyRepriceContractService.canOperate(unit);
    List<String> problems=canOperate && batch!=null ? workflow.problems(batch.getRepriceNo()).stream()
        .map(t->t.getOaNo()+" / "+t.getProductCode()+"："+t.getErrorMessage()).toList():List.of();
    // Saved check details include quotation identities; expose them only to authorized operators.
    if(batch!=null && !canOperate) batch.setCheckResultJson(null);
    return new View(batch,control.blocked(unit),canOperate,problems);
  }
  private static String unit() {
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();MonthlyRepriceContract.unit(unit);return unit;
  }
}
