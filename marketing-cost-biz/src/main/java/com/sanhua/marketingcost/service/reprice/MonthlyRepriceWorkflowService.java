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
      List<String> problems,com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.Statistics statistics) {}
  private final MonthlyQuotationControl control;
  private final MonthlyRepriceContractService contracts;
  private final MonthlyRepriceContractMapper mapper;
  private final MonthlyRepriceWorkflowMapper workflow;
  private final MonthlyRepricePublicationService publications;
  private final MonthlyRepriceProperties properties;
  private final com.sanhua.marketingcost.mapper.MonthlyRepriceUncalculatedMapper uncalculated;
  private final TransactionTemplate transaction;

  public MonthlyRepriceWorkflowService(MonthlyQuotationControl control,MonthlyRepriceContractService contracts,
      MonthlyRepriceContractMapper mapper,MonthlyRepriceWorkflowMapper workflow,MonthlyRepricePublicationService publications,
      MonthlyRepriceProperties properties,PlatformTransactionManager manager,
      com.sanhua.marketingcost.mapper.MonthlyRepriceUncalculatedMapper uncalculated) {
    this.control=control;this.contracts=contracts;this.mapper=mapper;this.workflow=workflow;
    this.publications=publications;this.properties=properties;this.uncalculated=uncalculated;transaction=new TransactionTemplate(manager);
    transaction.setTimeout(120);
  }
  public View current() {
    String unit=unit();
    var batch=workflow.active(unit);
    if(batch==null) batch=workflow.month(unit,com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth());
    return view(unit,batch);
  }
  public View start() {
    String unit=unit();MonthlyRepriceContractService.requireOperator(unit);
    if(!properties.isEnabled() || !properties.isBusinessUnitAllowed(unit))
      throw new IllegalStateException("当前业务单元尚未开放调价");
    return control.serialized(unit,()->view(unit,contracts.enqueue(unit,
        YearMonth.parse(com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth()))));
  }
  public View retry(String no,com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection selection) {
    String unit=unit();MonthlyRepriceContractService.requireOperator(unit);
    return control.serialized(unit,()->transaction.execute(tx->{
      requireBatch(no,unit);return view(unit,contracts.retry(no,selection));
    }));
  }
  public View confirm(String no,long revision) {
    String unit=unit();MonthlyRepriceContractService.requireOperator(unit);
    return view(unit,publications.confirm(no,revision));
  }

  public View recheck(String no) {
    String unit=unit();MonthlyRepriceContractService.requireOperator(unit);
    return control.serialized(unit,()->transaction.execute(tx->{
      requireBatch(no,unit);return view(unit,contracts.recheck(no));
    }));
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
    return new View(batch,control.blocked(unit),canOperate,problems,batch==null?null:uncalculated.statistics(batch.getId()));
  }
  private static String unit() {
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();MonthlyRepriceContract.unit(unit);return unit;
  }
}
