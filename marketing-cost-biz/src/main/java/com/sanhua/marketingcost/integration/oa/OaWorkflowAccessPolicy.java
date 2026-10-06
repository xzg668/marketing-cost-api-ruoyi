package com.sanhua.marketingcost.integration.oa;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/** 退回、结束通知控制办理范围；原报价员依据 OA 待办主动核算，不推断技术领导的审批结果。 */
@Service
public class OaWorkflowAccessPolicy {
  public record View(String state,String label,String reason,String syncError,boolean canCost,boolean canArrange) {}
  public record QuoterNode(long formId, long formVersion, long workflowVersion, String requestId,
      String workItemId, long actorId, String employeeNo) {}
  private final com.sanhua.marketingcost.service.costing.QuoteCostingSubmissionService technicalSubmissions;
  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;
  private final com.sanhua.marketingcost.security.PermissionService permissions;
  public OaWorkflowAccessPolicy(JdbcTemplate jdbc,OaMessageCodec codec,
      com.sanhua.marketingcost.security.PermissionService permissions,
      com.sanhua.marketingcost.service.costing.QuoteCostingSubmissionService technicalSubmissions) {
    this.technicalSubmissions = technicalSubmissions;
    this.jdbc=jdbc; this.codec=codec; this.permissions=permissions;
  }
  public View view(long formId) {
    var rows=jdbc.queryForList("""
      SELECT d.source_system,d.environment,d.source_version,s.* FROM lp_oa_quote_document d
      LEFT JOIN lp_oa_workflow_state s ON s.source_system=d.source_system AND s.environment=d.environment AND s.workflow_request_id=d.external_document_id
      WHERE d.oa_form_id=?
      """,formId);
    if(rows.isEmpty()) return null;
    var row=rows.getFirst();
    String state=Objects.toString(row.get("state"),"WAITING");
    String error=Objects.toString(row.get("sync_error"),null);
    boolean initial = row.get("id")==null || ((Number)row.get("applied_version")).longValue()==0;
    if(initial) state="COSTING";
    else if(!Objects.equals(((Number)row.get("source_version")).longValue(),((Number)row.get("form_version")).longValue())
        || ((Number)row.get("observed_form_version")).longValue()>((Number)row.get("source_version")).longValue()) error="需求版本与流程通知未同步，暂停办理";
    var auth=SecurityContextHolder.getContext().getAuthentication();
    String username=auth==null?null:auth.getPrincipal() instanceof UserDetails u?u.getUsername():auth.getName();
    var employee=jdbc.queryForList("SELECT employee_no FROM sys_user WHERE user_name=? AND status='0' AND del_flag='0' AND employee_no IS NOT NULL AND employee_no<>''",String.class,username);
    Set<String> roles=new HashSet<>();
    if(error==null && row.get("active_work_items_json")!=null) codec.read(row.get("active_work_items_json").toString()).forEach(w->{if(employee.contains(w.path("employeeNo").asText())) roles.add(w.path("nodeRole").asText());});
    boolean canCost=error==null && ((initial && !employee.isEmpty() && permissions.hasPermi("ingest:quote:cost-run:execute"))
        || (Set.of("COSTING","RECOSTING").contains(state) && roles.contains("COSTING"))
        || ("TECHNICAL".equals(state) && isOriginalQuoter(formId, username)
            && permissions.hasPermi("ingest:quote:cost-run:execute")));
    String submitted = finalSubmissionState(formId);
    if (submitted != null) {
      canCost = false;
      if ("SUBMITTED".equals(submitted)) state = "RESULT_APPROVAL";
      else if ("COMPLETED".equals(submitted)) state = "COMPLETED";
      else error = "本单成本正在提交或 OA 结果待确认，暂不能修改核算结果";
    }
    return new View(state,label(state),Objects.toString(row.get("reason"),null),error,canCost,canCost);
  }
  public void requireCosting(String oaNo) {
    var ids=jdbc.queryForList("SELECT id FROM oa_form WHERE oa_no=? AND deleted=0",Long.class,oaNo);
    if(!ids.isEmpty()) requireCosting(ids.getFirst());
  }
  public void requireCosting(long formId) {
    if (finalSubmissionState(formId) != null) throw new IllegalArgumentException("本单成本已提交或结果待确认，暂不能重新核算");
    var view=view(formId);
    // 无浏览器身份的后台执行仅再次校验流程是否仍允许核算；排队入口已经校验实际办理人。
    if(view!=null && SecurityContextHolder.getContext().getAuthentication()==null && view.syncError()==null
        && Set.of("COSTING","RECOSTING","TECHNICAL").contains(view.state())) return;
    if(view!=null && !view.canCost()) throw new IllegalArgumentException(view.syncError()!=null?view.syncError():"OA当前状态为"+view.label()+"，或本人不是有效办理人，不能核算或提交");
  }

  private String finalSubmissionState(long formId) {
    var states = jdbc.queryForList("SELECT status FROM lp_quote_final_submission WHERE oa_form_id=? ORDER BY id DESC LIMIT 1", String.class, formId);
    return !states.isEmpty() && Set.of("PENDING", "UNKNOWN", "SUBMITTED", "COMPLETED").contains(states.getFirst()) ? states.getFirst() : null;
  }

  /** 成本生成及最终提交使用同一份已提交、已由报价员采用的技术资料。 */
  public void requireCostPublication(long formId) {
    requireCostPublication(formId, null);
  }

  public void requireCostPublication(long formId, Long itemId) {
    requireCosting(formId);
    technicalSubmissions.requireAccepted(formId, itemId);
  }

  public void requireCostPublication(String oaNo) {
    var ids = jdbc.queryForList("SELECT id FROM oa_form WHERE oa_no=? AND deleted=0", Long.class, oaNo);
    if (!ids.isEmpty()) requireCostPublication(ids.getFirst());
  }

  /** 后台根据本地办理轮次及当前账号确定身份，不接受页面自报节点或工号。 */
  public QuoterNode quoterNode(long formId) {
    requireCosting(formId);
    var rows = jdbc.queryForList("""
        SELECT d.external_document_id,d.source_version,s.applied_version,s.state,s.active_work_items_json
        FROM lp_oa_quote_document d LEFT JOIN lp_oa_workflow_state s
          ON s.source_system=d.source_system AND s.environment=d.environment
          AND s.workflow_request_id=d.external_document_id WHERE d.oa_form_id=?
        """, formId);
    boolean initial = rows.size() == 1 && (rows.getFirst().get("applied_version") == null
        || ((Number) rows.getFirst().get("applied_version")).longValue() == 0);
    if (rows.size() != 1 || !initial && !Set.of("COSTING", "RECOSTING", "TECHNICAL").contains(rows.getFirst().get("state"))) {
      throw new IllegalArgumentException("OA 当前不在报价员办理阶段");
    }
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) throw new IllegalArgumentException("核算或退回必须由当前报价员主动办理");
    String username = authentication.getPrincipal() instanceof UserDetails details
        ? details.getUsername() : authentication.getName();
    var people = jdbc.queryForList("SELECT user_id,employee_no FROM sys_user WHERE user_name=? AND status='0' AND del_flag='0'", username);
    if (people.size() != 1) throw new IllegalArgumentException("当前报价员账号无效");
    var person = people.getFirst();
    String employee = Objects.toString(person.get("employee_no"), "").trim();
    if (employee.isEmpty()) throw new IllegalArgumentException("当前账号未维护工号，请先完善");
    var row = rows.getFirst();
    if (initial) {
      // I01 推送后由报价员检查和核算；最终提交成本才推动 OA。
      return new QuoterNode(formId, ((Number) row.get("source_version")).longValue(), 0,
          row.get("external_document_id").toString(), "LOCAL-INITIAL:" + formId + ":" + row.get("source_version"),
          ((Number) person.get("user_id")).longValue(), employee);
    }
    if ("TECHNICAL".equals(row.get("state"))) {
      return new QuoterNode(formId, ((Number) row.get("source_version")).longValue(),
          ((Number) row.get("applied_version")).longValue(), row.get("external_document_id").toString(),
          "LOCAL-TECHNICAL:" + formId + ":" + row.get("source_version") + ":" + row.get("applied_version"),
          ((Number) person.get("user_id")).longValue(), employee);
    }
    List<String> workItems = new ArrayList<>();
    codec.read(row.get("active_work_items_json").toString()).forEach(item -> {
      if ("COSTING".equals(item.path("nodeRole").asText()) && employee.equals(item.path("employeeNo").asText())) {
        workItems.add(item.path("workItemId").asText());
      }
    });
    if (workItems.size() != 1 || workItems.getFirst().isBlank()) {
      throw new IllegalArgumentException("当前报价员的有效办理任务不唯一，请核实 OA 流程通知");
    }
    return new QuoterNode(formId, ((Number) row.get("source_version")).longValue(),
        ((Number) row.get("applied_version")).longValue(), row.get("external_document_id").toString(),
        workItems.getFirst(), ((Number) person.get("user_id")).longValue(), employee);
  }

  private boolean isOriginalQuoter(long formId, String username) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM sys_user u WHERE u.user_name=? AND u.status='0' AND u.del_flag='0'
          AND u.employee_no IS NOT NULL AND u.employee_no<>'' AND u.user_id=(
            SELECT actor_user_id FROM lp_oa_technical_batch WHERE oa_form_id=? AND operation='I02'
              AND status='SUCCESS' ORDER BY created_at DESC,id DESC LIMIT 1))
        """, Boolean.class, username, formId));
  }

  private String label(String state) {
    return switch(state) {
      case "TECHNICAL" -> "技术补录";
      case "COSTING" -> "待核算"; case "RECOSTING" -> "待重新核算";
      case "RESULT_APPROVAL" -> "成本审批中"; case "SALES_REVISION" -> "待营业更正";
      case "COMPLETED" -> "已完成"; case "CANCELLED" -> "已取消";
      case "WITHDRAWN" -> "已撤回"; case "TERMINATED" -> "已终止"; default -> "待同步";
    };
  }
}
