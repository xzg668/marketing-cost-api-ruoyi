package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.ingest.*;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists the task's BOM applicability and result, without writing ordinary quotation status. */
@Service
public class MonthlyRepriceBomService {
  private final MonthlyRepriceContractMapper contracts;
  private final OaFormMapper forms;
  private final OaFormItemMapper items;
  private final QuoteBomContextResolver contexts;
  private final QuoteBomStatusService bom;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private static final Set<String> READY = Set.of("SYNCED", "REUSED_CURRENT_MONTH",
      "CURRENT_MONTH_QUOTED", "U9_BOM_EXISTS", "MANUAL_ENTERED");

  public MonthlyRepriceBomService(MonthlyRepriceContractMapper contracts, OaFormMapper forms,
      OaFormItemMapper items, QuoteBomContextResolver contexts, QuoteBomStatusService bom,
      ObjectMapper json, PlatformTransactionManager manager) {
    this.contracts=contracts; this.forms=forms; this.items=items; this.contexts=contexts;
    this.bom=bom; this.json=json; transaction=new TransactionTemplate(manager);
  }

  public record Input(OaForm form, OaFormItem item, QuoteBomContext context) {}
  public record Outcome(boolean ready, String problem, QuoteBomStatus bom) {}

  public Outcome prepare(String batchNo, long taskId) {
    // The brief task transaction fixes applicability before any potentially slow source acquisition.
    var saved = transaction.execute(tx -> {
      var task=requiredTask(batchNo, taskId);
      ObjectNode request=read(task);
      if (!request.has("bomInput")) {
        var item=items.selectById(task.getOaFormItemId());
        var form=item == null ? null : forms.selectById(item.getOaFormId());
        if (form == null || !Objects.equals(form.getOaNo(), task.getOaNo())
            || !Objects.equals(form.getBusinessUnitType(), task.getBusinessUnitType())
            || !Objects.equals(item.getBusinessUnitType(), task.getBusinessUnitType())
            || !Objects.equals(QuoteProductIdentityUtils.resolveCostingCode(item), task.getProductCode()))
          throw new QuoteIngestException("原报价单产品身份已变化，请核实保存版本归属后重试");
        var orgs=contracts.sourceOrganizations(request.path("sourceCostVersionId").asLong());
        if (orgs.size()!=1 || orgs.getFirst().priceOrgCode()==null
            || orgs.getFirst().materialOrganizationCode()==null)
          throw new QuoteIngestException("最近成功报价缺少唯一的数据组织依据，请核实后重试");
        var context=new QuoteBomContext(task.getPricingMonth(),task.getProductCode(),
            contexts.resolveCustomer(form,null),contexts.normalizePackageMethod(item.getPackageMethod()),orgs.getFirst());
        request.set("bomInput",json.valueToTree(new Input(form,item,context)));
        save(task,request);
      }
      return request;
    });
    if (saved.has("bomReady")) return new Outcome(true,null,convert(saved.get("bomReady"),QuoteBomStatus.class));
    Input input=convert(saved.get("bomInput"),Input.class);
    QuoteBomStatus result=bom.prepareMonthlyReprice(input.form(),input.item(),input.context());
    boolean ready=result != null && result.getBomStatus()!=null
        && READY.contains(result.getBomStatus()) && result.getSyncRecordId()!=null;
    if (!ready) return new Outcome(false,result == null ? "目标月BOM尚未取得"
        : result.getErrorMessage()==null ? "目标月BOM待图库、技术补录及确认" : result.getErrorMessage(),result);
    // Serialize publication; concurrent attempts return the same saved BOM reference.
    return transaction.execute(tx -> {
      var task=requiredTask(batchNo,taskId);
      var request=read(task);
      if (!request.has("bomReady")) { request.set("bomReady",json.valueToTree(result)); save(task,request); }
      return new Outcome(true,null,convert(request.get("bomReady"),QuoteBomStatus.class));
    });
  }

  private CostRunTask requiredTask(String batchNo,long id) {
    var batch=contracts.lock(batchNo);
    if (batch==null) throw new IllegalArgumentException("1130调价批次不存在");
    var task=contracts.readTask(batchNo,id);
    if (task==null) throw new IllegalArgumentException("任务不属于该调价批次");
    if (!Objects.equals(task.getBusinessUnitType(),batch.getBusinessUnitType())
        || !Objects.equals(task.getPricingMonth(),batch.getPricingMonth()))
      throw new IllegalStateException("任务的月份或业务单元与批次不一致");
    return task;
  }
  private ObjectNode read(CostRunTask task) {
    try {
      var node=json.readTree(task.getRequestSnapshotJson());
      if (!(node instanceof ObjectNode object) || object.path("sourceCostVersionId").asLong()<=0)
        throw new IllegalStateException("任务缺少保存的成功报价依据");
      return object;
    } catch (java.io.IOException ex) { throw new IllegalStateException("任务依据不可读取",ex); }
  }
  private void save(CostRunTask task,ObjectNode request) {
    if (contracts.saveRequest(task.getId(),request.toString())!=1)
      throw new IllegalStateException("调价BOM依据保存失败");
  }
  private <T> T convert(com.fasterxml.jackson.databind.JsonNode value,Class<T> type) {
    return json.convertValue(value,type);
  }
}
