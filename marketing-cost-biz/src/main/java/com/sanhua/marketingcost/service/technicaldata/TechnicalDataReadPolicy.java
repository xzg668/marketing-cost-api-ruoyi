package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 工作草稿仅本人可见；其他查看人只读取已成功送审的模块版本。 */
@Component
public class TechnicalDataReadPolicy {

  private final JdbcTemplate jdbc;

  public TechnicalDataReadPolicy(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean ownsDraft(QuoteTechModule module, TechnicalDataActor actor) {
    return (
      actor != null &&
      !actor.canViewSupplementOverview() &&
      Objects.equals(module.getAssigneeUserId(), actor.userId())
    );
  }

  public Long version(
    QuoteTechProduct product,
    QuoteTechModule module,
    TechnicalDataActor actor,
    Long requested
  ) {
    boolean owner = ownsDraft(module, actor);
    Long selected = owner
      ? module.getCurrentVersionId() != null
        ? module.getCurrentVersionId()
        : product.getCurrentEditVersionId()
      : submittedVersion(product.getId(), module.getModuleType(), null);
    if (requested != null && !Objects.equals(requested, selected)) {
      Long submitted = submittedVersion(product.getId(), module.getModuleType(), requested);
      if (submitted == null) throw forbidden();
      selected = submitted;
    }
    return selected;
  }

  /** 只处理查询对象，不写回数据库；隐藏草稿状态、参考内容及校验信息。 */
  public void project(QuoteTechModule module, Long selected, TechnicalDataActor actor) {
    if (ownsDraft(module, actor)) return;
    boolean pendingRevision = selected != null && !Objects.equals(selected, module.getCurrentVersionId())
        && java.util.Set.of("RETURNED", "EDITING", "READY").contains(module.getModuleStatus());
    projectReadOnly(module, selected);
    if (pendingRevision) module.setModuleStatus("RETURNED");
  }

  public void projectReadOnly(QuoteTechModule module, Long selected) {
    module.setCurrentVersionId(selected);
    module.setEntryMode(null);
    module.setReferenceSourceType(null);
    module.setReferenceSourceId(null);
    module.setReferenceSourceVersion(null);
    module.setReferenceFingerprint(null);
    module.setReferenceSnapshotJson(null);
    module.setReferencedAt(null);
    module.setLastValidationCode(null);
    module.setLastValidationMessage(null);
    module.setModuleStatus(
      selected == null
        ? Integer.valueOf(1).equals(module.getRequiredFlag())
          ? "PENDING"
          : "NOT_REQUIRED"
        : jdbc.queryForObject(
            "SELECT version_status FROM lp_quote_tech_data_version WHERE id=?",
            String.class,
            selected
          )
    );
  }

  public Long readVersion(
    QuoteTechProduct product,
    QuoteTechModule module,
    TechnicalDataActor actor,
    Long requested
  ) {
    Long selected = version(product, module, actor, requested);
    project(module, selected, actor);
    return selected;
  }

  public Long submittedVersion(long productId, String type, Long requested) {
    String sql = """
    SELECT s.technical_version_id FROM lp_quote_tech_submission s
    WHERE s.product_id=? AND s.sent_at IS NOT NULL AND s.submission_status IN ('SENT','APPROVED','RETURNED')
      AND JSON_CONTAINS(s.module_types_json,JSON_QUOTE(?))
    """;
    var rows =
      requested == null
        ? jdbc.queryForList(sql + " ORDER BY s.sent_at DESC,s.id DESC LIMIT 1", Long.class, productId, type)
        : jdbc.queryForList(sql + " AND s.technical_version_id=?", Long.class, productId, type, requested);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private TechnicalDataTaskException forbidden() {
    return new TechnicalDataTaskException(
      TechnicalDataTaskErrorCode.FORBIDDEN,
      "只能查看本人草稿或已提交的模块资料"
    );
  }
}
