package com.sanhua.marketingcost.security;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sanhua.marketingcost.security.QuoteVisibilityContext.Scope;

@Service
public class QuoteVisibilityPolicy {
  private final JdbcTemplate jdbc;
  public QuoteVisibilityPolicy(JdbcTemplate jdbc) { this.jdbc=jdbc; }
  public Scope resolve(Authentication auth) {
    if (auth == null || !auth.isAuthenticated()
        || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) return Scope.NONE;
    Set<String> authorities = new HashSet<>();
    auth.getAuthorities().forEach(a -> authorities.add(a.getAuthority().toLowerCase(Locale.ROOT)));
    // 菜单通配权限不代表真实管理员；普通报价员仍必须遵守账号范围。
    if (authorities.contains("role_admin") || authorities.contains("role_bu_director")) return Scope.ALL;
    // 外部OA办理人只使用原有单据与模块分派权限，不继承内部报价账号范围。
    if (auth.getPrincipal() instanceof com.sanhua.marketingcost.integration.oa.oauth.OaOAuthPrincipal) return Scope.ALL;
    String name=auth.getPrincipal() instanceof UserDetails u ? u.getUsername() : auth.getName();
    var rows=jdbc.queryForList("""
        SELECT COALESCE(q.quote_scope,'COMMERCIAL') AS quote_scope FROM sys_user u
        LEFT JOIN lp_quote_visibility_account q ON q.user_id=u.user_id
        WHERE u.user_name=? AND u.status='0' AND u.del_flag='0'
        """,String.class,name);
    return rows.size()==1 ? Scope.valueOf(rows.getFirst()) : Scope.NONE;
  }
  public boolean canReadForm(long id) {
    var values=jdbc.queryForList("SELECT f.id FROM oa_form f WHERE f.id=? AND f.deleted=0 AND "
        + QuoteVisibilityContext.formCondition("f"),Long.class,id);
    return values.size()==1;
  }
  public void requireForm(long id) {
    if (!QuoteVisibilityContext.canAccessForm(id)) throw denied();
  }
  public void require(String kind, Object value) {
    if (!QuoteVisibilityContext.restricted() || value==null) return;
    if ("ingestLog".equals(kind)) {
      var logs=jdbc.queryForList("SELECT l.id FROM lp_quote_ingest_log l WHERE l.id=? AND "
          + QuoteVisibilityContext.ingestLogCondition("l"),Long.class,value);
      if (logs.size()!=1) throw denied();
      return;
    }
    String sql=switch(kind) {
      case "oaNo" -> "SELECT id FROM oa_form WHERE oa_no=? AND deleted=0";
      case "form" -> "SELECT id FROM oa_form WHERE id=? AND deleted=0";
      case "item" -> "SELECT oa_form_id FROM oa_form_item WHERE id=? AND deleted=0";
      case "task" -> "SELECT oa_form_id FROM lp_quote_tech_task WHERE id=?";
      case "product" -> "SELECT t.oa_form_id FROM lp_quote_tech_product p JOIN lp_quote_tech_task t ON t.id=p.task_id WHERE p.id=?";
      case "returnBatch" -> "SELECT oa_form_id FROM lp_oa_technical_batch WHERE id=?";
      case "costRun" -> "SELECT f.id FROM lp_quote_cost_run_version v JOIN oa_form f ON BINARY f.oa_no=BINARY v.oa_no AND f.deleted=0 WHERE v.cost_run_no=?";
      case "costVersion" -> "SELECT f.id FROM lp_quote_cost_run_version v JOIN oa_form f ON BINARY f.oa_no=BINARY v.oa_no AND f.deleted=0 WHERE v.id=?";
      default -> throw new IllegalArgumentException("Unknown quotation identity");
    };
    var forms=jdbc.queryForList(sql,Long.class,value);
    if (forms.isEmpty() || forms.stream().anyMatch(id -> !QuoteVisibilityContext.canAccessForm(id))) throw denied();
  }
  public record AccountScope(long userId,String userName,String quoteScope,boolean allQuotes) {}
  public AccountScope account(long userId) {
    var rows=jdbc.queryForList("""
        SELECT u.user_name,COALESCE(q.quote_scope,'COMMERCIAL') AS quote_scope,
          EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id
            WHERE ur.user_id=u.user_id AND r.status='0' AND r.del_flag='0'
              AND LOWER(r.role_key) IN ('admin','bu_director')) AS all_quotes
        FROM sys_user u LEFT JOIN lp_quote_visibility_account q ON q.user_id=u.user_id
        WHERE u.user_id=? AND u.del_flag='0'
        """,userId);
    if (rows.size()!=1) throw new IllegalArgumentException("用户不存在");
    var row=rows.getFirst();return new AccountScope(userId,row.get("user_name").toString(),row.get("quote_scope").toString(),((Number)row.get("all_quotes")).intValue()==1);
  }
  @Transactional
  public AccountScope configure(long userId,String scope,String actor) {
    if (!Set.of("PLATE","COMMERCIAL").contains(Objects.toString(scope,""))) throw new IllegalArgumentException("报价范围只能是板换或商用");
    account(userId);
    jdbc.update("""
        INSERT INTO lp_quote_visibility_account(user_id,quote_scope,created_by,updated_by)
        VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE quote_scope=VALUES(quote_scope),updated_by=VALUES(updated_by),updated_at=CURRENT_TIMESTAMP
        """,userId,scope,actor,actor);
    return account(userId);
  }
  private AccessDeniedException denied() { return new AccessDeniedException("无权访问该报价范围的单据"); }
}
