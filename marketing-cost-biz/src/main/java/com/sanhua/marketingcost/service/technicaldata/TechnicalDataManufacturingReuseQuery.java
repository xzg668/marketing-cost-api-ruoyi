package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingResponse.ReusedItem;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Manufacturing;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.RawMaterial;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingSourceQuery.Assessment;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingSourceQuery.Node;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingSourceQuery.State;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 同单、同月、同组织的制造件单件资料复用；读取不写草稿，保存仍走本产品校验及审批。 */
@Service
public class TechnicalDataManufacturingReuseQuery {
  private final JdbcTemplate jdbc;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataReadPolicy readPolicy;
  private final TechnicalDataVersionContentCodec codec;
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final TechnicalDataManufacturingSourceQuery sources;
  private final OaMessageCodec fingerprints;

  public TechnicalDataManufacturingReuseQuery(JdbcTemplate jdbc, QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository tasks, TechnicalDataReadPolicy readPolicy, TechnicalDataVersionContentCodec codec,
      ElectronicDrawingWorkflowContextPort contexts, TechnicalDataManufacturingSourceQuery sources,
      OaMessageCodec fingerprints) {
    this.jdbc = jdbc; this.repository = repository; this.tasks = tasks; this.readPolicy = readPolicy;
    this.codec = codec; this.contexts = contexts; this.sources = sources; this.fingerprints = fingerprints;
  }

  public List<ReusedItem> find(QuoteTechTask task, QuoteTechProduct product, Assessment target,
      Manufacturing saved, TechnicalDataActor actor) {
    if (target == null || target.unavailableReason() != null || actor == null) return List.of();
    var missing = target.nodes().stream().filter(node -> node.state() == State.MISSING_RAW)
        .filter(node -> saved == null || saved.items().stream().noneMatch(row ->
            Objects.equals(row.sourceVersionId(), target.sourceVersionId())
                && Objects.equals(row.parentSourceNodeId(), node.sourceNodeId().toString())
                && Objects.equals(row.parentMaterialNo(), node.parentMaterialNo()))).toList();
    if (missing.isEmpty()) return List.of();
    var candidates = new ArrayList<ReusedItem>();
    // 从当前报价的产品范围查找，不能把他人未提交草稿或其他报价资料带入。
    var ids = jdbc.queryForList("""
        SELECT p.id FROM lp_quote_tech_product p JOIN lp_quote_tech_task t ON t.id=p.task_id
        JOIN lp_quote_tech_module m ON m.product_id=p.id AND m.module_type='MANUFACTURING'
        WHERE t.oa_form_id=? AND t.business_unit_type=? AND t.applicable_org_code=?
          AND p.accounting_month=? AND p.id<>? AND p.active_flag=1 AND t.active_flag=1
          AND t.task_status<>'CANCELLED' AND m.current_version_id IS NOT NULL
          AND m.module_status IN ('READY','FROZEN','SUBMITTED','APPROVED')
        ORDER BY p.id
        """, Long.class, task.getOaFormId(), task.getBusinessUnitType(), task.getApplicableOrgCode(),
        product.getAccountingMonth(), product.getId());
    for (Long id : ids) {
      var owner = repository.findProduct(id).orElseThrow();
      var ownerTask = repository.findTask(owner.getTaskId()).orElseThrow();
      var modules = tasks.findModules(id);
      if (!actor.canReadTask(ownerTask, modules)) continue;
      var module = modules.stream().filter(row -> "MANUFACTURING".equals(row.getModuleType())).findFirst().orElseThrow();
      Long selected = readPolicy.version(owner, module, actor, null);
      if (selected == null || !Objects.equals(selected, module.getCurrentVersionId())) continue;
      var version = repository.findVersion(selected).orElseThrow();
      var content = codec.manufacturing(version);
      if (!Objects.equals(version.getProductId(), id) || !TechnicalDataManufacturingRules.validate(content, owner).isEmpty()) continue;
      var context = contexts.load(owner.getOaFormItemId(), ownerTask.getBusinessUnitType(),
          ownerTask.getApplicableOrgCode(), owner.getAccountingMonth());
      if (!Objects.equals(context.sourceVersionId(), content.evidence().drawingSourceVersionId())) continue;
      var currentSource = sources.inspect(context);
      if (currentSource.hasUnresolved() || !sources.matchesFingerprint(currentSource, content.evidence().sourceFingerprint())) continue;
      for (var node : missing) for (var raw : content.items()) {
        if (!matches(node, raw) || currentSource.nodes().stream().noneMatch(source ->
            Objects.equals(source.sourceNodeId().toString(), raw.parentSourceNodeId()) && matches(source, raw))) continue;
        String reference = "TECH_MANUFACTURING:" + owner.getMaterialNo() + ":" + selected + ":"
            + raw.parentSourceNodeId() + ":" + fingerprints.dataFingerprint(raw);
        candidates.add(new ReusedItem(node.sourceNodeId(), id, owner.getMaterialNo(), selected, reference,
            raw.rawMaterialNo(), raw.rawMaterialDrawingNo(), raw.unit(), raw.grossWeightKg(), raw.netLengthMm(),
            raw.evidence().rawMaterialName(), raw.evidence().rawMaterialSpec()));
      }
    }
    var result = new ArrayList<ReusedItem>();
    for (var node : missing) {
      var rows = candidates.stream().filter(row -> Objects.equals(row.parentSourceNodeId(), node.sourceNodeId())).toList();
      // 同料号若有不同原料/单件用量，不能按产品顺序随便选一份。
      if (!rows.isEmpty() && rows.stream().allMatch(row -> sameValues(row, rows.getFirst()))) result.add(rows.getFirst());
    }
    return List.copyOf(result);
  }

  private boolean matches(Node node, RawMaterial raw) {
    if (node.state() != State.MISSING_RAW || !Objects.equals(node.parentMaterialNo(), raw.parentMaterialNo())
        || raw.evidence() == null || !Objects.equals(trim(node.drawingNo()), trim(raw.evidence().parentDrawingNo()))) return false;
    try {
      return same(TechnicalDataManufacturingUnits.sourceWeightG(node.sourceNetWeight(), node.sourceNetWeightUnit()),
          TechnicalDataManufacturingRules.calculationInput(raw).netWeightG());
    } catch (IllegalArgumentException exception) { return false; }
  }

  private boolean sameValues(ReusedItem left, ReusedItem right) {
    return Objects.equals(left.rawMaterialNo(), right.rawMaterialNo()) && Objects.equals(left.unit(), right.unit())
        && same(left.grossWeightKg(), right.grossWeightKg()) && same(left.netLengthMm(), right.netLengthMm());
  }

  private static boolean same(BigDecimal left, BigDecimal right) { return left != null && right != null && left.compareTo(right) == 0; }
  private static String trim(String value) { return value == null ? "" : value.trim(); }
}
