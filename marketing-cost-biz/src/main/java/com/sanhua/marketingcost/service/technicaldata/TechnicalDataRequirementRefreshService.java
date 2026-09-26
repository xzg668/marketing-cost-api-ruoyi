package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 来源复查仅更新可编辑任务的需求元数据，保留草稿内容及所有送审快照。调用方持有任务锁。 */
@Service
public class TechnicalDataRequirementRefreshService {
  private final QuoteTechModuleMapper modules;
  private final JdbcTemplate jdbc;
  private final QuoteTechTaskMapper tasks;
  private final com.sanhua.marketingcost.integration.oa.OaMessageCodec codec;

  public TechnicalDataRequirementRefreshService(QuoteTechModuleMapper modules, JdbcTemplate jdbc,
      QuoteTechTaskMapper tasks,
      com.sanhua.marketingcost.integration.oa.OaMessageCodec codec) {
    this.modules = modules;
    this.jdbc = jdbc;
    this.tasks = tasks;
    this.codec = codec;
  }

  /** 核算与财务复查共用。已送审和来源未确认的模块保持原状，不能静默取消审批。 */
  public String reconcile(Long itemId, String month, String organization,
      List<TechnicalDataModuleRequirement> requirements) {
    var task = tasks.selectActiveForUpdate(itemId, month);
    if (task == null) return null;
    if (!Objects.equals(task.getApplicableOrgCode(), organization)) {
      // 任务组织是不可变业务身份。只有从未分派、未填资料的空任务可以退出活动占用，随后按新组织重建。
      if (canRetireEmptyTask(task)) {
        cancelLocally(task);
        return "BOM 组织已由 " + task.getApplicableOrgCode() + " 更正为 " + organization
            + "，旧空任务已作废；进入补录时将按新组织建立任务";
      }
      return "当前 BOM 组织为 " + organization + "，原补录任务组织为 " + task.getApplicableOrgCode()
          + "；原任务已有分派或填报记录，已保留，请先核实原任务";
    }
    if (task.getOaAssignmentVersion() != null && task.getOaAssignmentVersion() > 0
        && !"PUBLISHED".equals(task.getExternalTaskStatus())) return "原任务的待办变更尚待 OA 确认，保留当前资料";
    var current = modules.selectByTaskId(task.getId());
    var changed = current.stream().filter(module -> requirements.stream().anyMatch(next ->
        next.moduleType().equals(module.getModuleType()) && changed(module, next))).toList();
    if (changed.isEmpty()) return null;
    if (!unassigned(task)) return "已分派任务保留原产品及模块范围，请在原任务查看";
    if (changed.stream().anyMatch(module -> locked(task, module))) return "来源已变化；原任务含送审资料，保留原版本，请在原任务核实";
    var unresolved = requirements.stream().filter(next -> next.availability() == TechnicalDataAvailability.ERROR
        || next.availability() == TechnicalDataAvailability.UNCONFIRMED).map(TechnicalDataModuleRequirement::moduleType).toList();
    if (changed.stream().anyMatch(module -> Integer.valueOf(1).equals(module.getRequiredFlag())
        && unresolved.contains(module.getModuleType()))) return "部分原待补模块尚未核实，保留原缺口及草稿";
    refresh(task, requirements);
    boolean remaining = requirements.stream().anyMatch(TechnicalDataModuleRequirement::required);
    if (unassigned(task)) {
      if (!remaining) cancelLocally(task);
      return remaining ? "未分派草稿已按最新来源更新，已有内容保留"
          : "已无确认缺口，未分派草稿退出待补录，已有内容与历史保留";
    }
    return null;
  }

  /** 未分派草稿尚未产生 OA 待办，来源复查只需维护本地任务，不能伪造办理人发起 OA 消息。 */
  private boolean unassigned(QuoteTechTask task) {
    return "UNASSIGNED".equals(task.getTaskStatus())
        && task.getAssigneeUserId() == null
        && task.getOaFlowId() == null;
  }

  private boolean canRetireEmptyTask(QuoteTechTask task) {
    if (!unassigned(task) || task.getExternalTaskStatus() != null
        || task.getOaAssignmentVersion() != null && task.getOaAssignmentVersion() > 0) return false;
    return Boolean.FALSE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_quote_tech_data_version v
          JOIN lp_quote_tech_product p ON p.id=v.product_id WHERE p.task_id=?)
          OR EXISTS(SELECT 1 FROM lp_quote_tech_submission WHERE task_id=?)
          OR EXISTS(SELECT 1 FROM lp_quote_tech_oa_recipient WHERE task_id=?)
        """, Boolean.class, task.getId(), task.getId(), task.getId()));
  }

  private void cancelLocally(QuoteTechTask task) {
    jdbc.update("UPDATE lp_quote_tech_product SET active_flag=0,active_lock_key=NULL,row_version=row_version+1,updated_at=NOW(3) WHERE task_id=? AND active_flag=1", task.getId());
    jdbc.update("UPDATE lp_quote_tech_task SET active_flag=0,active_lock_key=NULL,task_status='CANCELLED',task_version=task_version+1,updated_at=NOW(3) WHERE id=?", task.getId());
  }

  private boolean changed(QuoteTechModule module, TechnicalDataModuleRequirement next) {
    return !Objects.equals(module.getSourceAvailability(), next.availability().name())
        || !sameReference(module.getSourceReference(), next.sourceReference());
  }

  private boolean sameReference(String previous, String current) {
    if (Objects.equals(previous, current)) return true;
    // Map 的 JSON 字段顺序可能随 JVM 重启变化，不能因此撤销已完成状态或误报批准来源变化。
    if (previous != null && current != null && previous.stripLeading().startsWith("{")
        && current.stripLeading().startsWith("{")) {
      return codec.read(previous).equals(codec.read(current));
    }
    return false;
  }

  private boolean locked(QuoteTechTask task, QuoteTechModule module) {
    return Set.of("FROZEN", "SUBMITTED", "APPROVED").contains(module.getModuleStatus())
        || Set.of("PREPARED", "SUBMITTED", "APPROVED").contains(task.getTaskStatus());
  }

  public void refresh(QuoteTechTask task, List<TechnicalDataModuleRequirement> requirements) {
    if (task.getOaAssignmentVersion() != null && task.getOaAssignmentVersion() > 0) return;
    var current = modules.selectByTaskId(task.getId());
    Map<String, TechnicalDataModuleRequirement> byType = requirements.stream()
        .collect(Collectors.toMap(TechnicalDataModuleRequirement::moduleType, Function.identity()));
    for (var module : current) {
      var next = byType.get(module.getModuleType());
      if (next == null || !changed(module, next)) continue;
      if (locked(task, module)) {
        throw conflict("产品已有送审资料，来源变化须先在原任务核实，不能覆盖冻结版本");
      }
      if (Integer.valueOf(1).equals(module.getRequiredFlag())
          && Set.of(TechnicalDataAvailability.ERROR, TechnicalDataAvailability.UNCONFIRMED).contains(next.availability())) {
        throw conflict("原待补模块的来源尚未重新确认，请核实后再分派，原草稿已保留");
      }
      String status = next.availability() == TechnicalDataAvailability.AVAILABLE ? "NOT_REQUIRED"
          : module.getCurrentVersionId() == null ? "PENDING" : "EDITING";
      if (modules.refreshRequirement(module.getId(), module.getRowVersion(), next, status) != 1) {
        throw conflict("补录资料已被另一人更新，请刷新后再分派");
      }
    }
  }

  private TechnicalDataTaskException conflict(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message);
  }
}
