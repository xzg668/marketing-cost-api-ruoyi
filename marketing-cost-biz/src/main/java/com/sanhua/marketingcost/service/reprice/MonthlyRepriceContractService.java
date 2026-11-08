package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.*;
import java.time.YearMonth;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.costing.MonthlyCostSnapshotDocuments;
import com.sanhua.marketingcost.config.MonthlyRepriceProperties;
import java.util.function.Supplier;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional scope, task and check transitions. Publication has its own boundary.
 * Scope discovery and costing remain separate; supplied sources are verified against saved versions.
 */
@Service
public class MonthlyRepriceContractService {
  private final MonthlyRepriceContractMapper mapper;
  private final MonthlyRepriceScopeRegistration scopes;
  private final com.sanhua.marketingcost.mapper.MonthlyRepricePublicationMapper publications;
  private final TransactionTemplate transaction;
  private final ObjectMapper json;
  private final MonthlyCostSnapshotDocuments documents;
  private final MonthlyRepriceProperties properties;
  private final com.sanhua.marketingcost.service.pricing.inquiry.InquiryPriceRefreshService inquiryPrices;
  private final com.sanhua.marketingcost.mapper.MonthlyPriceImportMapper priceFolders;

  public MonthlyRepriceContractService(MonthlyRepriceContractMapper mapper,
      PlatformTransactionManager manager, ObjectMapper json, MonthlyCostSnapshotDocuments documents,
      MonthlyRepriceProperties properties,
      com.sanhua.marketingcost.service.pricing.inquiry.InquiryPriceRefreshService inquiryPrices,
      com.sanhua.marketingcost.mapper.MonthlyPriceImportMapper priceFolders,
      MonthlyRepriceScopeRegistration scopes,
      com.sanhua.marketingcost.mapper.MonthlyRepricePublicationMapper publications) {
    this.mapper = mapper;
    this.scopes = scopes;
    this.publications = publications;
    this.json = json;
    this.documents = documents;
    this.properties = properties;
    this.inquiryPrices = inquiryPrices;
    this.priceFolders = priceFolders;
    transaction = new TransactionTemplate(manager);
    transaction.setTimeout(30);
  }

  /** One immutable monthly identity; duplicate submissions return its existing state. */
  public MonthlyRepriceBatch enqueue(String unit, YearMonth month) {
    YearMonth goLive = properties.requireGoLiveMonth(unit);
    return enqueue(unit, month, goLive, () -> mapper.cumulativeSources(unit,
        goLive.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay()));
  }

  /** Internal contract tests may select a subset; each identity still requires its actual latest success. */
  MonthlyRepriceBatch enqueue(String unit, YearMonth month, YearMonth goLive, List<Source> sources) {
    return enqueue(unit, month, goLive, () -> {
      var latest = new java.util.HashMap<String,QuoteCostRunVersion>();
      for(var v:mapper.cumulativeSources(unit,goLive.atDay(1).atStartOfDay(),month.plusMonths(1).atDay(1).atStartOfDay()))
        latest.put(new ObjectIdentity(unit,v.getOaNo(),v.getOaFormItemId()).key(),v);
      var selected=new java.util.ArrayList<QuoteCostRunVersion>();
      for(var input:sources) {
        if(!unit.equals(input.object().businessUnitType()))
          throw new IllegalArgumentException("跨业务单元或重复调价对象");
        var v=latest.get(input.object().key());
        if(v==null || v.getId()!=input.sourceCostVersionId())
          throw new IllegalArgumentException("必须使用上线以来该报价单产品行的最近成功报价版本");
        selected.add(v);
      }
      return List.copyOf(selected);
    });
  }

  private MonthlyRepriceBatch enqueue(String unit, YearMonth month, YearMonth goLive,
      Supplier<List<QuoteCostRunVersion>> discover) {
    String actor = requireOperator(unit);
    Objects.requireNonNull(month); Objects.requireNonNull(goLive);
    if (!month.toString().matches("[0-9]{4}-[0-9]{2}") || !goLive.toString().matches("[0-9]{4}-[0-9]{2}"))
      throw new IllegalArgumentException("月份必须为四位年份及两位月份");
    if (month.isBefore(goLive)) throw new IllegalArgumentException("调价月份早于实际上线月份");
    return transaction.execute(tx -> {
      requireFolderComplete(unit, month.toString());
      String no = "MRP1130-" + UUID.randomUUID();
      mapper.reserve(no, unit, month.toString(), goLive.toString(), actor);
      var batch = mapper.month(unit, month.toString());
      if (batch == null) throw new IllegalStateException("本业务单元存在未确认调价，失败亦须修正后重试");
      if (!goLive.toString().equals(batch.getGoLiveMonth()))
        throw new IllegalStateException("既有批次的实际上线范围不可更改");
      if (!no.equals(batch.getRepriceNo())) return batch;
      var inputs = discover.get();
      if (inputs.isEmpty()) throw new IllegalArgumentException("上线以来没有可调价的成功报价");
      inquiryPrices.refreshInTransaction(unit, month.toString(), "MONTHLY_REPRICE", actor);
      mapper.queue(no, unit, month.toString(), actor, 0);
      scopes.register(batch,inputs);
      syncQueue(no);
      return mapper.lock(no);
    });
  }

  /** Only a first successful confirmation releases quotation admission. */
  public boolean blocksQuotation(String unit) {
    MonthlyRepriceContract.unit(unit);
    return mapper.blocked(unit) > 0;
  }

  public void fail(String no, long taskId, int execution, String worker, String message) {
    if (message == null || message.isBlank() || message.length() > 1000)
      throw new IllegalArgumentException("失败原因必填且最多1000字符");
    transaction.executeWithoutResult(tx -> {
      var batch = required(no);
      if (!canReceiveTask(batch))
        throw new IllegalStateException("当前批次不能写入任务失败");
      task(no, taskId);
      if (mapper.fail(taskId, execution, worker, message) != 1)
        throw new IllegalStateException("任务执行轮次已失效");
      progress(no,State.FAILED,0,1);
      syncQueue(no);
    });
  }

  /** Explicitly selected objects only; concurrent workers on other customers may keep running. */
  public MonthlyRepriceBatch retry(String no,com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection selection) {
    Objects.requireNonNull(selection);
    return transaction.execute(tx -> {
      var batch=required(no);String actor=requireOperator(batch.getBusinessUnitType());
      requireFolderComplete(batch.getBusinessUnitType(),batch.getPricingMonth());
      var tasks=selection.taskIds().isEmpty()?List.<CostRunTask>of():mapper.selectedTasks(no,selection.taskIds());
      if(tasks.size()!=selection.taskIds().size())throw new IllegalArgumentException("所选客户对象不属于该调价批次或对象资料不完整");
      publications.pinLegacyResults(no);publications.pinLegacyRevision(no);
      var changed=scopes.repairMissingCustomers(batch,selection.sourceIds(),actor);
      var failed=tasks.stream().filter(t->"FAILED".equals(t.getStatus())).map(CostRunTask::getId).toList();
      int retried=failed.isEmpty()?0:mapper.retry(no,failed);
      if(changed.resolved()==0 && retried==0)return mapper.lock(no);
      inquiryPrices.refreshInTransaction(batch.getBusinessUnitType(),batch.getPricingMonth(),"MONTHLY_REPRICE_RETRY",actor);
      var current=mapper.lock(no);
      int success=current.getSuccessCount()+changed.successDelta(), failures=current.getFailedCount()+changed.failedDelta()-retried;
      State next=failures>0?State.FAILED:current.getTotalCount()>success?State.RUNNING:
          current.getTotalCount()>0?State.CHECKING:State.FAILED;
      progress(no,next,changed.successDelta(),changed.failedDelta()-retried);
      syncQueue(no);return mapper.lock(no);
    });
  }

  public MonthlyRepriceBatch recheck(String no) {
    return transaction.execute(tx->{
      var batch=required(no);requireOperator(batch.getBusinessUnitType());
      if(state(batch)!=State.FAILED && state(batch)!=State.RUNNING)throw new IllegalStateException("仅检查失败的批次可重新检查");
      progress(no,State.CHECKING,0,0);syncQueue(no);return mapper.lock(no);
    });
  }

  private void requireFolderComplete(String unit, String month) {
    if (priceFolders.unfinished(unit, month) > 0)
      throw new IllegalStateException("该月份价格文件夹尚未处理完成，请先修正报错并补齐上月规则");
  }

  /** Result and task point to the same complete, batch-owned version in one transaction. */
  public void complete(String no, long taskId, int execution, String worker, long versionId) {
    transaction.executeWithoutResult(tx -> {
      var batch = required(no);
      var task = task(no, taskId);
      if (task.getExecutionNo() != execution) throw new IllegalStateException("任务执行轮次已失效");
      if ("SUCCESS".equals(task.getStatus()) && Objects.equals(task.getCostRunVersionId(), versionId)) return;
      if (!"RUNNING".equals(task.getStatus()) || (!canReceiveTask(batch)))
        throw new IllegalStateException("任务或批次不能关联结果");
      if (!mapper.ownsLiveTask(no,taskId,execution,worker))
        throw new IllegalStateException("调价任务租约或持有者已失效");
      var version = validateVersion(batch, task, versionId);
      if (mapper.link(no, task.getCalcObjectKey(), version) != 1
          || mapper.succeed(taskId, execution, worker, version) != 1)
        throw new IllegalStateException("结果关联冲突");
      State next = batch.getFailedCount()>0 ? State.FAILED
          : batch.getSuccessCount()+1==batch.getTotalCount() ? State.CHECKING : State.RUNNING;
      progress(no,next,1,0);
      syncQueue(no);
    });
  }

  /** Publish the checker report in a short revision-fenced transaction; no BOM reads under this lock. */
  public MonthlyRepriceBatch recordCheck(MonthlyRepriceCheckService.Report report) {
    String no=report.repriceNo();long expectedRevision=report.resultRevision();boolean passed=report.passed();
    var findings=report.evidence();
    if (findings == null || findings.isEmpty()) throw new IllegalArgumentException("检查依据不能为空");
    final String evidence;
    try { evidence = json.writeValueAsString(findings); }
    catch (JsonProcessingException ex) { throw new IllegalArgumentException("检查依据不可序列化", ex); }
    return transaction.execute(tx -> {
      var batch = required(no);
      if (state(batch) != State.CHECKING || batch.getResultRevision() != expectedRevision)
        throw new IllegalStateException("检查针对的结果已变化");
      if (passed && (report.objects()!=batch.getTotalCount() || report.checkedSuccess()!=batch.getSuccessCount()))
        throw new IllegalStateException("检查对象与批次进度不一致");
      State next = passed ? State.WAIT_CONFIRM : State.FAILED;
      mapper.check(no, next.name(), passed ? "PASSED" : "FAILED", evidence);
      syncQueue(no);
      return mapper.lock(no);
    });
  }

  private QuoteCostRunVersion validateVersion(MonthlyRepriceBatch batch, CostRunTask task, long id) {
    var v = mapper.version(id);
    if (v == null || !batch.getRepriceNo().equals(mapper.versionOwner(id))
        || !batch.getBusinessUnitType().equals(v.getBusinessUnitType())
        || !batch.getPricingMonth().equals(v.getPricingMonth())
        || !task.getOaNo().equals(v.getOaNo()) || !task.getOaFormItemId().equals(v.getOaFormItemId())
        || !task.getProductCode().equals(v.getProductCode()) || mapper.completeVersion(id) != 1)
      throw new IllegalStateException("成本版本不完整、归属错误或尚未成功");
    var snapshot = documents.read(batch.getBusinessUnitType(), mapper.snapshot(id));
    MonthlyCostVersionService.validateSavedSnapshot(v, snapshot);
    return v;
  }

  private void syncQueue(String no) {
    var batch = mapper.lock(no);
    int success=batch.getSuccessCount(),failed=batch.getFailedCount(),total=batch.getTotalCount();
    String status = "FAILED".equals(batch.getStatus()) ? "FAILED"
        : success == total ? "SUCCESS" : "QUEUED".equals(batch.getStatus()) ? "PENDING" : "RUNNING";
    mapper.queueState(no,status,success,failed,total==0 ? 0 : (int)(success*100L/total));
  }

  /** Caller holds the batch lock and has changed exactly the fenced task rows in this transaction. */
  private void progress(String no,State status,int successDelta,int failedDelta) {
    if(mapper.progress(no,status.name(),successDelta,failedDelta)!=1)
      throw new IllegalStateException("调价进度与任务变更不一致");
  }

  private MonthlyRepriceBatch required(String no) {
    var batch = mapper.lock(no);
    if (batch == null) throw new IllegalArgumentException("1130调价批次不存在");
    return batch;
  }

  private CostRunTask task(String no, long id) {
    var task=mapper.readTask(no,id);
    if(task==null)throw new IllegalArgumentException("任务不属于该调价批次");
    return task;
  }

  private static boolean canReceiveTask(MonthlyRepriceBatch batch) {
    return state(batch)!=State.QUEUED;
  }

  private static State state(MonthlyRepriceBatch batch) { return State.valueOf(batch.getStatus()); }

  static boolean canOperate(String unit) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.isAuthenticated() && unit != null
        && unit.equals(BusinessUnitContext.getCurrentBusinessUnitType())
        && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority())
            || "ROLE_BU_DIRECTOR".equalsIgnoreCase(a.getAuthority()));
  }

  static String requireOperator(String unit) {
    MonthlyRepriceContract.unit(unit);
    if (!canOperate(unit))
      throw new AccessDeniedException("必须由管理员或当前业务单元总监操作月度调价");
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
