package com.sanhua.marketingcost.integration.oa;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/** OA当前待办约束业务办理；普通登录权限仍由原有鉴权检查。PDF导入不受OA通知约束。 */
@Service
public class OaWorkflowAccessPolicy {
  public record View(String state,String label,String reason,String syncError,boolean canCost,boolean canArrange) {}
  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;
  public OaWorkflowAccessPolicy(JdbcTemplate jdbc,OaMessageCodec codec) { this.jdbc=jdbc;this.codec=codec; }
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
    if(row.get("id")==null || ((Number)row.get("applied_version")).longValue()==0) error="等待OA初始流程通知";
    else if(!Objects.equals(((Number)row.get("source_version")).longValue(),((Number)row.get("form_version")).longValue())
        || ((Number)row.get("observed_form_version")).longValue()>((Number)row.get("source_version")).longValue()) error="需求版本与流程通知未同步，暂停办理";
    var auth=SecurityContextHolder.getContext().getAuthentication();
    String username=auth==null?null:auth.getPrincipal() instanceof UserDetails u?u.getUsername():auth.getName();
    var employee=jdbc.queryForList("SELECT m.external_user_id FROM lp_oa_user_mapping m JOIN sys_user u ON u.user_id=m.user_id WHERE m.source_system=? AND m.environment=? AND u.user_name=? AND u.status='0' AND u.del_flag='0'",String.class,row.get("source_system"),row.get("environment"),username);
    Set<String> roles=new HashSet<>();
    if(error==null && row.get("active_work_items_json")!=null) codec.read(row.get("active_work_items_json").toString()).forEach(w->{if(employee.contains(w.path("employeeNo").asText())) roles.add(w.path("nodeRole").asText());});
    boolean canCost=error==null && ((state.equals("MATERIAL_REVIEW") && roles.contains("MATERIAL")) || (Set.of("COSTING","RECOSTING").contains(state) && roles.contains("COSTING")));
    return new View(state,label(state),Objects.toString(row.get("reason"),null),error,canCost,canCost);
  }
  public void requireCosting(String oaNo) {
    var ids=jdbc.queryForList("SELECT id FROM oa_form WHERE oa_no=? AND deleted=0",Long.class,oaNo);
    if(!ids.isEmpty()) requireCosting(ids.getFirst());
  }
  public void requireCosting(long formId) {
    var view=view(formId);
    // 无浏览器身份的后台执行仅再次校验流程是否仍允许核算；排队入口已经校验实际办理人。
    if(view!=null && SecurityContextHolder.getContext().getAuthentication()==null && view.syncError()==null
        && Set.of("MATERIAL_REVIEW","COSTING","RECOSTING").contains(view.state())) return;
    if(view!=null && !view.canCost()) throw new IllegalArgumentException(view.syncError()!=null?view.syncError():"OA当前状态为"+view.label()+"，或本人不是有效办理人，不能核算或提交");
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
