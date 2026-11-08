package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
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
import com.sanhua.marketingcost.service.costing.ProductCostingExecutionGuard;
import com.sanhua.marketingcost.service.costing.ProductCostingContext;
import com.sanhua.marketingcost.service.impl.CostRunPublicInputPreparation;
import com.sanhua.marketingcost.service.quotebom.MonthlyBomSnapshotDetailService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyQuotationPrices;
import com.sanhua.marketingcost.service.concurrent.RetryOnConcurrency;
import com.sanhua.marketingcost.entity.CostRunTask;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Supplier;
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
  private final ProductCostingExecutionGuard executionGuard;
  private final TransactionTemplate transaction;
  private final MonthlyRepriceBomReadScope bomReads;
  private final CostRunPublicInputPreparation publicInputs;
  private final MonthlyBomSnapshotDetailService bomDetails;
  private final MonthlyQuotationPrices monthlyPrices;

  public MonthlyRepriceCostingService(MonthlyRepriceContractMapper tasks,
      MonthlyRepriceContractService contracts, MonthlyRepriceBomService boms,
      QuoteEffectiveBomApplicationService effective, QuoteEffectiveBomPersistenceService persistence,
      QuoteProductBomCostingBuildService settlement, PricePrepareService prices,
      FinancePricePrepareService finance, QuoteCuAdjustmentCalcService costing,
      QuoteCostRunVersionMapper versions, MonthlyCostVersionService savedCosts,
      ObjectMapper json, PlatformTransactionManager manager,
      ProductCostingExecutionGuard executionGuard,
      MonthlyRepriceBomReadScope bomReads,
      CostRunPublicInputPreparation publicInputs,
      MonthlyBomSnapshotDetailService bomDetails,
      MonthlyQuotationPrices monthlyPrices) {
    this.tasks=tasks; this.contracts=contracts; this.boms=boms; this.effective=effective;
    this.persistence=persistence; this.settlement=settlement; this.prices=prices; this.finance=finance;
    this.costing=costing; this.versions=versions; this.savedCosts=savedCosts; this.json=json;
    this.executionGuard=executionGuard;
    this.bomReads=bomReads; this.publicInputs=publicInputs; this.bomDetails=bomDetails; this.monthlyPrices=monthlyPrices;
    transaction=new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(120);
  }

  /** Caller supplies the leased execution number; repeat delivery of success reads the same version. */
  @RetryOnConcurrency
  public MonthlyCostVersionService.Stored execute(Lease lease) {
    return executeBatch(List.of(lease)).getFirst();
  }

  public record Lease(String repriceNo, long taskId, int execution, String workerId) {
    public static Lease from(CostRunTask task) {
      return new Lease(task.getSourceNo(),task.getId(),task.getExecutionNo(),task.getWorkerId());
    }
  }

  /** Bounded input preparation; each customer's result still has its own commit boundary. */
  @RetryOnConcurrency
  public List<MonthlyCostVersionService.Stored> executeBatch(List<Lease> leases) {
    var work = List.copyOf(leases);
    if (work.size() > 64) throw new IllegalArgumentException("调价准备每批最多64个对象");
    if (work.stream().map(Lease::taskId).distinct().count() != work.size())
      throw new IllegalArgumentException("同批调价对象不能重复");
    var active = new LinkedHashMap<Lease, MonthlyRepriceCalculation>();
    var completed = new LinkedHashMap<Lease, MonthlyCostVersionService.Stored>();
    for (var lease : work) {
      var task = requiredTask(lease);
      if ("SUCCESS".equals(task.getStatus())) {
        completed.put(lease, savedCosts.read(task.getBusinessUnitType(), task.getCostRunVersionId()));
        continue;
      }
      var prepared = boms.prepare(lease.repriceNo(), lease.taskId());
      if (!prepared.ready()) throw new MonthlyRepriceInputNotReady(prepared.problem());
      task = requiredTask(lease);
      var input = readInput(task.getRequestSnapshotJson());
      var month = YearMonth.parse(task.getPricingMonth());
      var batch = Objects.requireNonNull(tasks.readBatch(lease.repriceNo()), "调价批次不存在");
      var asOf = YearMonth.from(batch.getPriceAsOfTime()).equals(month) ? batch.getPriceAsOfTime()
          : month.atEndOfMonth().atTime(23,59,59);
      active.put(lease, new MonthlyRepriceCalculation(lease.repriceNo(), task.getCalcObjectKey(), input, prepared.bom(), asOf));
    }
    var preparedFees = publicInputs.prepare(active.values().stream().map(input ->
        new CostRunPublicInputPreparation.Request(input.costContext(),
            Set.of(input.input().context().productCode()))).toList());
    var frozenBoms = bomDetails.loadBatch(active.values().stream().map(input -> input.bom().getSyncRecordId()).toList());
    return bomDetails.useLoaded(frozenBoms, () -> {
      var prepared = new LinkedHashMap<Lease, MonthlyRepriceCalculation>();
      var builds = new LinkedHashMap<Lease, String>();
      var demands = new ArrayList<MonthlyQuotationPrices.Demand>();
      int index = 0;
      for (var entry : active.entrySet()) {
        var calculation = entry.getValue().withPreparedInputs(preparedFees.get(index++));
        var lease = entry.getKey();
        var build = prepareBom(lease,calculation);
        if (build == null) {
          var task = requiredTask(lease);
          completed.put(lease,savedCosts.read(task.getBusinessUnitType(),task.getCostRunVersionId()));
          continue;
        }
        prepared.put(lease,calculation);
        builds.put(lease,build);
        demands.add(bomReads.use(calculation, () -> prices.repriceDemand(calculation,build)));
      }
      var selectedPrices = monthlyPrices.prepareBatch(demands);
      index = 0;
      for (var entry : prepared.entrySet()) {
        var lease = entry.getKey();
        completed.put(lease, monthlyPrices.usePrepared(selectedPrices.get(index++),
            () -> executePrepared(lease,entry.getValue(),builds.get(lease))));
      }
      return work.stream().map(completed::get).toList();
    });
  }

  private MonthlyCostVersionService.Stored executePrepared(Lease lease, MonthlyRepriceCalculation calculation,
      String build) {
    return guarded(calculation, () -> transaction.execute(tx -> {
      var task = requiredTask(lease);
      if ("SUCCESS".equals(task.getStatus()))
        return savedCosts.read(task.getBusinessUnitType(),task.getCostRunVersionId());
      requireUnchanged(task,calculation);
      var input = calculation.input();
      return bomReads.use(calculation, () -> {
        var priceRequest=new PricePrepareGenerateRequest();
        priceRequest.setOaNo(task.getOaNo()); priceRequest.setOaFormItemId(task.getOaFormItemId());
        priceRequest.setTopProductCode(task.getProductCode()); priceRequest.setPeriodMonth(task.getPricingMonth());
        priceRequest.setBusinessUnitType(task.getBusinessUnitType()); priceRequest.setPriceAsOfTime(calculation.priceAsOfTime());
        var preparedPrices=prices.generateForReprice(priceRequest,calculation,build);
        if (!"SUCCESS".equals(preparedPrices.getStatus()) || preparedPrices.getGapCount()!=0)
          throw new MonthlyRepriceInputNotReady("调价价格未齐全："+preparedPrices.getMessage(),preparedPrices.getIssues());
        finance.generateFromOa(preparedPrices.getPrepareNo());
        var result=costing.calculate(new QuoteCuAdjustmentCalcRequest(input.form(),input.item(),
            task.getPricingMonth(),preparedPrices.getPrepareNo(),task.getCalcObjectKey(),null,true,calculation));
        var version=result.version();
        version.setStatus("SUCCESS"); version.setVersionNo("MRP-"+version.getId());
        version.setConfirmMessage("月度调价核算完成，等待业务总监确认批次");
        if (versions.updateById(version)!=1) throw new IllegalStateException("调价版本发布失败");
        contracts.complete(lease.repriceNo(),lease.taskId(),lease.execution(),lease.workerId(),version.getId());
        return savedCosts.read(task.getBusinessUnitType(),version.getId());
      });
    }));
  }

  private String prepareBom(Lease lease, MonthlyRepriceCalculation calculation) {
    return guarded(calculation, () -> transaction.execute(tx -> {
      var task = requiredTask(lease);
      if ("SUCCESS".equals(task.getStatus())) return null;
      requireUnchanged(task,calculation);
      return bomReads.use(calculation, () -> {
        var candidate=effective.prepareRepriceCandidate(calculation);
        var tree=persistence.persistCurrentVariant(new QuoteEffectiveBomPersistenceRequest(
            calculation.bom().getSyncRecordId(),null,candidate.alternativeSelectionIdByGroupKey(),candidate.candidateVariant()));
        settlement.buildForReprice(calculation,tree.buildBatchId());
        return tree.buildBatchId();
      });
    }));
  }

  private <T> T guarded(MonthlyRepriceCalculation calculation, Supplier<T> work) {
    var input = calculation.input();
    var scope = new ProductCostingContext(input.form(),input.item(),
        input.context().productCode(),input.context().costPeriodMonth(),"MONTHLY_REPRICE",null);
    return executionGuard.execute(scope,work);
  }

  private void requireUnchanged(CostRunTask task,
      MonthlyRepriceCalculation calculation) {
    var input=readInput(task.getRequestSnapshotJson());
    if (!json.valueToTree(input).equals(json.valueToTree(calculation.input()))
        || !Objects.equals(task.getCalcObjectKey(),calculation.objectKey()))
      throw new IllegalStateException("调价固定输入已变化，必须重新批量解析");
  }

  private CostRunTask requiredTask(Lease lease) {
    var task = tasks.readTask(lease.repriceNo(), lease.taskId());
    if (task == null || !Objects.equals(task.getExecutionNo(), lease.execution()))
      throw new IllegalStateException("调价任务不存在或执行轮次已失效");
    if (!"SUCCESS".equals(task.getStatus()) && !"RUNNING".equals(task.getStatus()))
      throw new IllegalStateException("调价任务尚未领取");
    if ("RUNNING".equals(task.getStatus())
        && !tasks.ownsLiveTask(lease.repriceNo(),lease.taskId(),lease.execution(),lease.workerId()))
      throw new IllegalStateException("调价任务租约或持有者已失效");
    return task;
  }

  private MonthlyRepriceBomService.Input readInput(String saved) {
    try {
      return json.treeToValue(json.readTree(saved).required("bomInput"),MonthlyRepriceBomService.Input.class);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("保存的调价输入不可读取",invalid);
    }
  }

}
