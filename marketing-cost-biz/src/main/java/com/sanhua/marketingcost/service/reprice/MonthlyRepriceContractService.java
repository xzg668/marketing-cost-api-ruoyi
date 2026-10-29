package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.*;
import java.time.YearMonth;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import com.sanhua.marketingcost.config.MonthlyRepriceProperties;
import java.util.function.Supplier;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional contract boundary. No controller or worker calls this until the subsequent stages.
 * Scope discovery and costing remain separate; supplied sources are verified against saved versions.
 */
@Service
public class MonthlyRepriceContractService {
  private final MonthlyRepriceContractMapper mapper;
  private final TransactionTemplate transaction;
  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyRepriceProperties properties;

  public MonthlyRepriceContractService(MonthlyRepriceContractMapper mapper,
      PlatformTransactionManager manager, ObjectMapper json, MonthlyPriceDocumentCodec codec,
      MonthlyRepriceProperties properties) {
    this.mapper = mapper;
    this.json = json;
    this.codec = codec;
    this.properties = properties;
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
      String no = "MRP1130-" + UUID.randomUUID();
      mapper.reserve(no, unit, month.toString(), goLive.toString(), actor);
      var batch = mapper.month(unit, month.toString());
      if (batch == null) throw new IllegalStateException("本业务单元存在未确认调价，失败亦须修正后重试");
      if (!goLive.toString().equals(batch.getGoLiveMonth()))
        throw new IllegalStateException("既有批次的实际上线范围不可更改");
      if (!no.equals(batch.getRepriceNo())) return batch;
      var inputs = discover.get();
      if (inputs.isEmpty()) throw new IllegalArgumentException("上线以来没有可调价的成功报价");
      var targets=new java.util.ArrayList<MonthlyRepriceContractMapper.Target>();
      Set<String> keys = new HashSet<>();
      for (var source : inputs) {
        var identity=new ObjectIdentity(source.getBusinessUnitType(),source.getOaNo(),source.getOaFormItemId());
        if (!unit.equals(identity.businessUnitType()) || !keys.add(identity.key()))
          throw new IllegalArgumentException("调价对象跨业务单元或重复");
        targets.add(new MonthlyRepriceContractMapper.Target(identity.key(),source));
      }
      mapper.queue(no, unit, month.toString(), actor, targets.size());
      for(int start=0;start<targets.size();start+=200) {
        var chunk=targets.subList(start,Math.min(start+200,targets.size()));
        mapper.tasksBatch(no,month.toString(),chunk);
        mapper.objectsBatch(no,month.toString(),chunk);
      }
      mapper.total(no, targets.size());
      return mapper.lock(no);
    });
  }

  /** Used by the later entry-point guard; failure and review never release this control. */
  public boolean blocksQuotation(String unit) {
    MonthlyRepriceContract.unit(unit);
    return mapper.blocked(unit) > 0;
  }

  public int begin(String no, long taskId) {
    return transaction.execute(tx -> {
      var batch = required(no);
      State state = state(batch);
      if (state != State.QUEUED && state != State.RUNNING)
        throw new IllegalStateException("当前批次不能领取任务");
      var task = task(no, taskId);
      if (mapper.begin(taskId, task.getExecutionNo()) != 1)
        throw new IllegalStateException("任务已被领取或结束");
      if (state == State.QUEUED) state.requireTransition(State.RUNNING);
      mapper.state(no, State.RUNNING.name());
      syncQueue(no);
      return task.getExecutionNo();
    });
  }

  public void fail(String no, long taskId, int execution, String message) {
    if (message == null || message.isBlank() || message.length() > 1000)
      throw new IllegalArgumentException("失败原因必填且最多1000字符");
    transaction.executeWithoutResult(tx -> {
      var batch = required(no);
      if (state(batch) != State.RUNNING && state(batch) != State.FAILED)
        throw new IllegalStateException("当前批次不能写入任务失败");
      task(no, taskId);
      if (mapper.fail(taskId, execution, message) != 1)
        throw new IllegalStateException("任务执行轮次已失效");
      mapper.state(no, State.FAILED.name());
      syncQueue(no);
    });
  }

  /** Failed tasks retry in place; successful tasks/versions and the monthly identity are retained. */
  public MonthlyRepriceBatch retry(String no) {
    return transaction.execute(tx -> {
      var batch = required(no); requireOperator(batch.getBusinessUnitType());
      if (state(batch) != State.FAILED) throw new IllegalStateException("仅失败批次可重试");
      var tasks = mapper.tasks(no);
      if (tasks.stream().anyMatch(t -> "RUNNING".equals(t.getStatus())))
        throw new IllegalStateException("等待已领取任务结束后重试");
      mapper.retry(no);
      State next = tasks.stream().allMatch(t -> "SUCCESS".equals(t.getStatus())) ? State.CHECKING : State.QUEUED;
      state(batch).requireTransition(next);
      mapper.state(no, next.name());
      syncQueue(no);
      return mapper.lock(no);
    });
  }

  /** Result and task point to the same complete, batch-owned version in one transaction. */
  public void complete(String no, long taskId, int execution, long versionId) {
    transaction.executeWithoutResult(tx -> {
      var batch = required(no);
      var task = task(no, taskId);
      if (task.getExecutionNo() != execution) throw new IllegalStateException("任务执行轮次已失效");
      if ("SUCCESS".equals(task.getStatus()) && Objects.equals(task.getCostRunVersionId(), versionId)) return;
      if (!"RUNNING".equals(task.getStatus()) || (state(batch) != State.RUNNING && state(batch) != State.FAILED))
        throw new IllegalStateException("任务或批次不能关联结果");
      var version = validateVersion(batch, task, versionId);
      if (mapper.link(no, task.getCalcObjectKey(), version) != 1
          || mapper.succeed(taskId, execution, version) != 1)
        throw new IllegalStateException("结果关联冲突");
      var tasks = mapper.tasks(no);
      State next = tasks.stream().anyMatch(t -> "FAILED".equals(t.getStatus())) ? State.FAILED
          : tasks.stream().allMatch(t -> "SUCCESS".equals(t.getStatus())) ? State.CHECKING : State.RUNNING;
      mapper.state(no, next.name());
      syncQueue(no);
    });
  }

  /** Later business checks supply the findings; revision/completeness are enforced here as well. */
  public MonthlyRepriceBatch recordCheck(String no, long expectedRevision, boolean passed, Map<String, Object> findings) {
    if (findings == null || findings.isEmpty()) throw new IllegalArgumentException("检查依据不能为空");
    final String evidence;
    try { evidence = json.writeValueAsString(findings); }
    catch (JsonProcessingException ex) { throw new IllegalArgumentException("检查依据不可序列化", ex); }
    return transaction.execute(tx -> {
      var batch = required(no);
      if (state(batch) != State.CHECKING || batch.getResultRevision() != expectedRevision)
        throw new IllegalStateException("检查针对的结果已变化");
      if (passed) validateSet(batch);
      State next = passed ? State.WAIT_CONFIRM : State.FAILED;
      state(batch).requireTransition(next);
      mapper.check(no, next.name(), passed ? "PASSED" : "FAILED", evidence);
      syncQueue(no);
      return mapper.lock(no);
    });
  }

  public MonthlyRepriceBatch confirm(String no, long checkedRevision) {
    return transaction.execute(tx -> {
      var batch = required(no);
      String actor = requireOperator(batch.getBusinessUnitType());
      if (!Objects.equals(batch.getCheckedRevision(), checkedRevision)
          || !Objects.equals(batch.getResultRevision(), checkedRevision))
        throw new IllegalStateException("必须确认当前检查通过的结果版本");
      if (state(batch) == State.CONFIRMED) return batch;
      state(batch).requireTransition(State.CONFIRMED);
      if (!"PASSED".equals(batch.getCheckStatus())) throw new IllegalStateException("检查未通过");
      validateSet(batch);
      if (mapper.confirm(no, checkedRevision, actor) != 1) throw new IllegalStateException("确认冲突");
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
    var snapshot = codec.decode(mapper.snapshot(id), MonthlyCostVersionService.Snapshot.class);
    MonthlyCostVersionService.validateSavedSnapshot(v, snapshot);
    return v;
  }

  private void validateSet(MonthlyRepriceBatch batch) {
    var tasks = mapper.tasks(batch.getRepriceNo());
    var results = mapper.results(batch.getRepriceNo());
    if (tasks.isEmpty() || tasks.size() != batch.getTotalCount() || results.size() != tasks.size())
      throw new IllegalStateException("任务/结果集合不完整");
    for (var task : tasks) {
      if (!"SUCCESS".equals(task.getStatus()) || task.getCostRunVersionId() == null)
        throw new IllegalStateException("尚有未成功任务");
      var result = results.stream().filter(r -> task.getCalcObjectKey().equals(r.getCalcObjectKey())).findFirst().orElseThrow();
      var version = validateVersion(batch, task, task.getCostRunVersionId());
      if (!Objects.equals(result.getCostRunVersionId(), version.getId()) || !"SUCCESS".equals(result.getCalcStatus())
          || !Objects.equals(result.getOaFormItemId(), task.getOaFormItemId())
          || !Objects.equals(result.getOaNo(), task.getOaNo())
          || !Objects.equals(result.getBusinessUnitType(), batch.getBusinessUnitType())
          || !Objects.equals(result.getPricingMonth(), batch.getPricingMonth())
          || !Objects.equals(result.getProductCode(), task.getProductCode())
          || !Objects.equals(result.getSourceCostVersionId(), sourceVersion(task))
          || result.getTotalCost().compareTo(version.getTotalCost()) != 0)
        throw new IllegalStateException("任务、结果与保存版本不一致");
    }
  }

  private long sourceVersion(CostRunTask task) {
    try {
      var source = json.readTree(task.getRequestSnapshotJson()).get("sourceCostVersionId");
      if (source == null || !source.isIntegralNumber() || !source.canConvertToLong() || source.longValue() <= 0)
        throw new IllegalStateException("任务缺少首次报价版本依据");
      return source.longValue();
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("任务保存报价依据不可读取", ex);
    }
  }

  private void syncQueue(String no) {
    var batch = mapper.lock(no);
    var tasks = mapper.tasks(no);
    int success = (int) tasks.stream().filter(t -> "SUCCESS".equals(t.getStatus())).count();
    int failed = (int) tasks.stream().filter(t -> "FAILED".equals(t.getStatus())).count();
    String status = "FAILED".equals(batch.getStatus()) ? "FAILED"
        : success == tasks.size() ? "SUCCESS" : "QUEUED".equals(batch.getStatus()) ? "PENDING" : "RUNNING";
    mapper.queueState(no, status, success, failed, tasks.isEmpty() ? 0 : success * 100 / tasks.size());
  }

  private MonthlyRepriceBatch required(String no) {
    var batch = mapper.lock(no);
    if (batch == null) throw new IllegalArgumentException("1130调价批次不存在");
    return batch;
  }

  private CostRunTask task(String no, long id) {
    return mapper.tasks(no).stream().filter(t -> t.getId() == id).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("任务不属于该调价批次"));
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
