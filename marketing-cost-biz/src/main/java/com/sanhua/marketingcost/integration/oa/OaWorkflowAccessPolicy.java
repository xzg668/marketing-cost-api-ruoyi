package com.sanhua.marketingcost.integration.oa;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/** OA通知决定办理阶段；实际分派/提交记录决定人员，普通业务权限仍需校验。 */
@Service
public class OaWorkflowAccessPolicy {
  public record View(String state,String label,String reason,String syncError,boolean canCost,boolean canArrange) {}
  public record MaterialNode(long formId, long formVersion, long workflowVersion, String requestId,
      String workItemId, long actorId, String employeeNo) {}
  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;
  private final com.sanhua.marketingcost.security.PermissionService permissions;
  public OaWorkflowAccessPolicy(JdbcTemplate jdbc,OaMessageCodec codec,
      com.sanhua.marketingcost.security.PermissionService permissions) {
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
    if(initial) state="MATERIAL_REVIEW";
    else if(!Objects.equals(((Number)row.get("source_version")).longValue(),((Number)row.get("form_version")).longValue())
        || ((Number)row.get("observed_form_version")).longValue()>((Number)row.get("source_version")).longValue()) error="需求版本与流程通知未同步，暂停办理";
    var auth=SecurityContextHolder.getContext().getAuthentication();
    String username=auth==null?null:auth.getPrincipal() instanceof UserDetails u?u.getUsername():auth.getName();
    var employee=jdbc.queryForList("SELECT employee_no FROM sys_user WHERE user_name=? AND status='0' AND del_flag='0' AND employee_no IS NOT NULL AND employee_no<>''",String.class,username);
    Set<String> roles=new HashSet<>();
    if(error==null && row.get("active_work_items_json")!=null) codec.read(row.get("active_work_items_json").toString()).forEach(w->{if(employee.contains(w.path("employeeNo").asText())) roles.add(w.path("nodeRole").asText());});
    boolean canCost=error==null && ((initial && !employee.isEmpty() && permissions.hasPermi("ingest:quote:cost-run:execute"))
        || (state.equals("MATERIAL_REVIEW") && roles.contains("MATERIAL")) || (Set.of("COSTING","RECOSTING").contains(state) && roles.contains("COSTING")));
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
        && Set.of("MATERIAL_REVIEW","COSTING","RECOSTING").contains(view.state())) return;
    if(view!=null && !view.canCost()) throw new IllegalArgumentException(view.syncError()!=null?view.syncError():"OA当前状态为"+view.label()+"，或本人不是有效办理人，不能核算或提交");
  }

  private String finalSubmissionState(long formId) {
    var states = jdbc.queryForList("SELECT status FROM lp_quote_final_submission WHERE oa_form_id=? ORDER BY id DESC LIMIT 1", String.class, formId);
    return !states.isEmpty() && Set.of("PENDING", "UNKNOWN", "SUBMITTED", "COMPLETED").contains(states.getFirst()) ? states.getFirst() : null;
  }

  /** 成本生成的最终门禁；资料检查允许在资料节点执行，成本发布须已完成本轮 I06。 */
  public void requireCostPublication(long formId) {
    requireCosting(formId);
    var current = view(formId);
    if (current != null && "MATERIAL_REVIEW".equals(current.state()) && !materialConfirmed(formId)) {
      throw new IllegalArgumentException("整单资料尚未确认，请使用“确认资料并核算”入口");
    }
  }

  public void requireCostPublication(String oaNo) {
    var ids = jdbc.queryForList("SELECT id FROM oa_form WHERE oa_no=? AND deleted=0", Long.class, oaNo);
    if (!ids.isEmpty()) requireCostPublication(ids.getFirst());
  }

  /** 后台根据本地办理轮次及当前账号确定身份，不接受页面自报节点或工号。 */
  public MaterialNode materialNode(long formId) {
    requireCosting(formId);
    var rows = jdbc.queryForList("""
        SELECT d.external_document_id,d.source_version,s.applied_version,s.state,s.active_work_items_json
        FROM lp_oa_quote_document d LEFT JOIN lp_oa_workflow_state s
          ON s.source_system=d.source_system AND s.environment=d.environment
          AND s.workflow_request_id=d.external_document_id WHERE d.oa_form_id=?
        """, formId);
    boolean initial = rows.size() == 1 && (rows.getFirst().get("applied_version") == null
        || ((Number) rows.getFirst().get("applied_version")).longValue() == 0);
    if (rows.size() != 1 || !initial && !"MATERIAL_REVIEW".equals(rows.getFirst().get("state"))) {
      throw new IllegalArgumentException("OA 当前不在报价员资料节点，不能发送 I06");
    }
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) throw new IllegalArgumentException("资料确认必须由当前报价员主动办理");
    String username = authentication.getPrincipal() instanceof UserDetails details
        ? details.getUsername() : authentication.getName();
    var people = jdbc.queryForList("SELECT user_id,employee_no FROM sys_user WHERE user_name=? AND status='0' AND del_flag='0'", username);
    if (people.size() != 1) throw new IllegalArgumentException("当前报价员账号无效");
    var person = people.getFirst();
    String employee = Objects.toString(person.get("employee_no"), "").trim();
    if (employee.isEmpty()) throw new IllegalArgumentException("当前账号未维护工号，请先完善");
    var row = rows.getFirst();
    if (initial) {
      // I01 已明确进入报价员资料等待阶段；四状态协议无需额外的初始节点通知。
      return new MaterialNode(formId, ((Number) row.get("source_version")).longValue(), 0,
          row.get("external_document_id").toString(), "LOCAL-INITIAL:" + formId + ":" + row.get("source_version"),
          ((Number) person.get("user_id")).longValue(), employee);
    }
    List<String> workItems = new ArrayList<>();
    codec.read(row.get("active_work_items_json").toString()).forEach(item -> {
      if ("MATERIAL".equals(item.path("nodeRole").asText()) && employee.equals(item.path("employeeNo").asText())) {
        workItems.add(item.path("workItemId").asText());
      }
    });
    if (workItems.size() != 1 || workItems.getFirst().isBlank()) {
      throw new IllegalArgumentException("当前报价员的有效资料待办不唯一，请核实 OA 流程通知");
    }
    return new MaterialNode(formId, ((Number) row.get("source_version")).longValue(),
        ((Number) row.get("applied_version")).longValue(), row.get("external_document_id").toString(),
        workItems.getFirst(), ((Number) person.get("user_id")).longValue(), employee);
  }

  /** 成功回执不伪造 OA 通知；通知尚未到达时，匹配当前待办的 I06 成功记录允许继续核算。 */
  public boolean materialConfirmed(long formId) {
    boolean initialConfirmed = Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_oa_quote_document d
          LEFT JOIN lp_oa_workflow_state s ON s.source_system=d.source_system AND s.environment=d.environment
            AND s.workflow_request_id=d.external_document_id
          JOIN lp_oa_material_confirmation c ON c.oa_form_id=d.oa_form_id AND c.form_version=d.source_version
        WHERE d.oa_form_id=? AND COALESCE(s.applied_version,0)=0 AND s.sync_error IS NULL
          AND c.status='SUCCESS' AND c.material_work_item_id=CONCAT('LOCAL-INITIAL:',d.oa_form_id,':',d.source_version))
        """, Boolean.class, formId));
    if (initialConfirmed) return true;
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_oa_quote_document d LEFT JOIN lp_oa_workflow_state s
          ON s.source_system=d.source_system AND s.environment=d.environment
          AND s.workflow_request_id=d.external_document_id
          JOIN lp_oa_material_confirmation c ON c.oa_form_id=d.oa_form_id AND c.form_version=d.source_version
        WHERE d.oa_form_id=? AND c.status='SUCCESS' AND s.sync_error IS NULL
          AND s.form_version=d.source_version AND s.observed_form_version=d.source_version
          AND ((s.state='MATERIAL_REVIEW' AND EXISTS(
            SELECT 1 FROM JSON_TABLE(s.active_work_items_json,'$[*]' COLUMNS(
              work_item VARCHAR(128) PATH '$.workItemId', role_name VARCHAR(32) PATH '$.nodeRole')) w
            WHERE w.role_name='MATERIAL' AND w.work_item=c.material_work_item_id))
            OR s.state IN ('COSTING','RECOSTING')))
        """, Boolean.class, formId));
  }
  private String label(String state) {
    return switch(state) {
      case "MATERIAL_REVIEW" -> "待资料确认"; case "TECHNICAL" -> "技术补录／审批中";
      case "COSTING" -> "待核算"; case "RECOSTING" -> "待重新核算";
      case "RESULT_APPROVAL" -> "成本审批中"; case "SALES_REVISION" -> "待营业更正";
      case "COMPLETED" -> "已完成"; case "CANCELLED" -> "已取消";
      case "WITHDRAWN" -> "已撤回"; case "TERMINATED" -> "已终止"; default -> "待同步";
    };
  }
}
