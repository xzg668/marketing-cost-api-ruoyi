package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepricePublicationMapper;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Check saved costs outside the quotation mutex; publish only an unchanged checked revision. */
@Service
public class MonthlyRepricePublicationService {
  private final MonthlyRepriceContractMapper batches;
  private final MonthlyRepricePublicationMapper publications;
  private final MonthlyRepriceCheckService checks;
  private final MonthlyQuotationControl control;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  public MonthlyRepricePublicationService(MonthlyRepriceContractMapper batches,
      MonthlyRepricePublicationMapper publications,MonthlyRepriceCheckService checks,
      MonthlyQuotationControl control,ObjectMapper json,PlatformTransactionManager manager) {
    this.batches=batches;this.publications=publications;this.checks=checks;this.control=control;this.json=json;
    transaction=new TransactionTemplate(manager);transaction.setTimeout(30);
  }

  public MonthlyRepriceBatch confirm(String no,long revision) {
    if(TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("确认检查不能置于外层业务事务中");
    var observed=required(batches.readBatch(no));
    String actor=MonthlyRepriceContractService.requireOperator(observed.getBusinessUnitType());
    requireRevision(observed,revision);
    if(alreadyPublished(observed,revision)) return observed;
    requireWaiting(observed);
    var report=checks.verify(observed);
    if(!report.passed()) throw new IllegalStateException("确认检查失败："+String.join("；",report.problems()));
    return control.serialized(observed.getBusinessUnitType(),()->transaction.execute(tx->{
      var current=required(batches.lock(no));
      MonthlyRepriceContractService.requireOperator(current.getBusinessUnitType());
      requireRevision(current,revision);
      if(alreadyPublished(current,revision)) return current;
      requireWaiting(current);
      if(!no.equals(report.repriceNo()) || report.resultRevision()!=revision
          || report.objects()!=current.getTotalCount() || report.checkedSuccess()!=current.getSuccessCount()
          || report.checkedSuccess()==0 || publications.readyCount(no)!=report.checkedSuccess())
        throw new IllegalStateException("当前检查的成功结果已变化，或尚无可确认结果");
      String before=summary(current);
      // Stamp only newly published costs. A pinned legacy cost may have no per-version confirmation metadata.
      publications.recordVersionPublication(no,revision,actor);
      int published=publications.publishResults(no);
      if(publications.confirm(no,revision,report.checkedSuccess(),actor)!=1)
        throw new IllegalStateException("确认冲突，请刷新当前结果");
      var confirmed=batches.lock(no);
      if(publications.audit(no,revision,actor,before,summary(confirmed),
          "本次发布"+published+"个结果，已核算"+report.checkedSuccess()+"，未核算客户产品"+(report.objects()-report.checkedSuccess())
            +"，待补客户来源"+Objects.requireNonNullElse(current.getUnresolvedSourceCount(),0))!=1)
        throw new IllegalStateException("确认审计未保存");
      return confirmed;
    }));
  }

  private String summary(MonthlyRepriceBatch batch) {
    try {return json.writeValueAsString(Map.of("resultRevision",batch.getResultRevision(),
        "publishedRevision",Objects.toString(batch.getPublishedRevision(),""),
        "success",batch.getSuccessCount(),"objects",batch.getTotalCount(),"status",batch.getStatus(),
        "unresolvedSources",Objects.requireNonNullElse(batch.getUnresolvedSourceCount(),0)));}
    catch(JsonProcessingException failure) {throw new IllegalStateException("确认依据无法保存",failure);}
  }
  private static MonthlyRepriceBatch required(MonthlyRepriceBatch batch) {
    if(batch==null) throw new IllegalArgumentException("调价批次不存在");
    return batch;
  }
  private static void requireRevision(MonthlyRepriceBatch batch,long revision) {
    if(!Objects.equals(batch.getResultRevision(),revision) || !Objects.equals(batch.getCheckedRevision(),revision))
      throw new IllegalStateException("必须确认当前检查通过的结果版本");
  }
  private static boolean alreadyPublished(MonthlyRepriceBatch batch,long revision) {
    return batch.getConfirmedAt()!=null && "CONFIRMED".equals(batch.getStatus())
        && (batch.getPublishedRevision()==null || Objects.equals(batch.getPublishedRevision(),revision));
  }
  private static void requireWaiting(MonthlyRepriceBatch batch) {
    if(!"WAIT_CONFIRM".equals(batch.getStatus()) || !"PASSED".equals(batch.getCheckStatus()))
      throw new IllegalStateException("必须确认当前检查通过的结果版本");
  }
}
