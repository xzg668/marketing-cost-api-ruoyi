package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.FinancePricePrepareService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuoteCuAdjustmentCalcService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomPersistenceRequest;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomPersistenceService;
import java.time.YearMonth;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared API/worker execution: existing BOM/price/cost rules with an independent publication boundary. */
@Service
public class MonthlyRepriceCostingService {
  private final MonthlyRepriceContractMapper tasks;
  private final MonthlyRepriceContractService contracts;
  private final MonthlyRepriceBomService boms;
  private final QuoteEffectiveBomApplicationService effective;
  private final QuoteEffectiveBomPersistenceService persistence;
  private final QuoteProductBomCostingBuildService settlement;
  private final PricePrepareService prices;
  private final FinancePricePrepareService finance;
  private final QuoteCuAdjustmentCalcService costing;
  private final QuoteCostRunVersionMapper versions;
  private final MonthlyCostVersionService savedCosts;
  private final ObjectMapper json;
  private final com.sanhua.marketingcost.service.costing.ProductCostingExecutionGuard executionGuard;
  private final TransactionTemplate transaction;

  public MonthlyRepriceCostingService(MonthlyRepriceContractMapper tasks,
      MonthlyRepriceContractService contracts, MonthlyRepriceBomService boms,
      QuoteEffectiveBomApplicationService effective, QuoteEffectiveBomPersistenceService persistence,
      QuoteProductBomCostingBuildService settlement, PricePrepareService prices,
      FinancePricePrepareService finance, QuoteCuAdjustmentCalcService costing,
      QuoteCostRunVersionMapper versions, MonthlyCostVersionService savedCosts,
      ObjectMapper json, PlatformTransactionManager manager,
      com.sanhua.marketingcost.service.costing.ProductCostingExecutionGuard executionGuard) {
    this.tasks=tasks; this.contracts=contracts; this.boms=boms; this.effective=effective;
    this.persistence=persistence; this.settlement=settlement; this.prices=prices; this.finance=finance;
    this.costing=costing; this.versions=versions; this.savedCosts=savedCosts; this.json=json;
    this.executionGuard=executionGuard;
    transaction=new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(120);
  }

  /** Caller supplies the leased execution number; repeat delivery of success reads the same version. */
  @com.sanhua.marketingcost.service.concurrent.RetryOnConcurrency
  public MonthlyCostVersionService.Stored execute(String no, long taskId, int execution) {
    var initial=tasks.readTask(no,taskId);
    if (initial == null || !Objects.equals(initial.getExecutionNo(),execution))
      throw new IllegalStateException("调价任务不存在或执行轮次已失效");
    if ("SUCCESS".equals(initial.getStatus()))
      return savedCosts.read(initial.getBusinessUnitType(),initial.getCostRunVersionId());
    if (!"RUNNING".equals(initial.getStatus())) throw new IllegalStateException("调价任务尚未领取");
    var prepared=boms.prepare(no,taskId);
    if (!prepared.ready()) throw new IllegalStateException(prepared.problem());
    var savedInput=readInput(tasks.readTask(no,taskId).getRequestSnapshotJson());
    var scope=new com.sanhua.marketingcost.service.costing.ProductCostingContext(savedInput.form(),savedInput.item(),
        initial.getProductCode(),initial.getPricingMonth(),"MONTHLY_REPRICE",null);
    // Share the ordinary per-item/month guard. No task or batch row stays locked during computation.
    return executionGuard.execute(scope, () -> transaction.execute(tx -> {
      var task=tasks.readTask(no,taskId);
      if (task == null || !Objects.equals(task.getExecutionNo(),execution))
        throw new IllegalStateException("调价任务执行轮次已失效");
      if ("SUCCESS".equals(task.getStatus()))
        return savedCosts.read(task.getBusinessUnitType(),task.getCostRunVersionId());
      if (!"RUNNING".equals(task.getStatus())) throw new IllegalStateException("调价任务不在执行中");
      var input=readInput(task.getRequestSnapshotJson());
      var month=YearMonth.parse(task.getPricingMonth());
      // A saved target-month source cutoff is stable across retries and belongs to the target month.
      var batch=tasks.readBatch(no);
      var asOf=YearMonth.from(batch.getPriceAsOfTime()).equals(month) ? batch.getPriceAsOfTime()
          : month.atEndOfMonth().atTime(23,59,59);
      var calculation=new MonthlyRepriceCalculation(no,task.getCalcObjectKey(),input,prepared.bom(),asOf);
      var candidate=effective.prepareRepriceCandidate(calculation);
      var tree=persistence.persistCurrentVariant(new QuoteEffectiveBomPersistenceRequest(
          prepared.bom().getSyncRecordId(),null,candidate.alternativeSelectionIdByGroupKey(),candidate.candidateVariant()));
      settlement.buildForReprice(calculation,tree.buildBatchId());
      var priceRequest=new PricePrepareGenerateRequest();
      priceRequest.setOaNo(task.getOaNo()); priceRequest.setOaFormItemId(task.getOaFormItemId());
      priceRequest.setTopProductCode(task.getProductCode()); priceRequest.setPeriodMonth(task.getPricingMonth());
      priceRequest.setBusinessUnitType(task.getBusinessUnitType()); priceRequest.setPriceAsOfTime(asOf);
      var preparedPrices=prices.generateForReprice(priceRequest,calculation,tree.buildBatchId());
      if (!"SUCCESS".equals(preparedPrices.getStatus()) || preparedPrices.getGapCount()!=0)
        throw new IllegalStateException("调价价格未齐全："+preparedPrices.getMessage());
      finance.generateFromOa(preparedPrices.getPrepareNo());
      var result=costing.calculate(new QuoteCuAdjustmentCalcRequest(input.form(),input.item(),
          task.getPricingMonth(),preparedPrices.getPrepareNo(),task.getCalcObjectKey(),null,true,calculation));
      var version=result.version();
      version.setStatus("SUCCESS"); version.setVersionNo("MRP-"+version.getId());
      version.setConfirmMessage("月度调价核算完成，等待业务总监确认批次");
      if (versions.updateById(version)!=1) throw new IllegalStateException("调价版本发布失败");
      contracts.complete(no,taskId,execution,version.getId());
      return savedCosts.read(task.getBusinessUnitType(),version.getId());
    }));
  }
  private MonthlyRepriceBomService.Input readInput(String saved) {
    try {
      return json.treeToValue(json.readTree(saved).required("bomInput"),MonthlyRepriceBomService.Input.class);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("保存的调价输入不可读取",invalid);
    }
  }

}
