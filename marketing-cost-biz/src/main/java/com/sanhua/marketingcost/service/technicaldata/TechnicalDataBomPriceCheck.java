package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirementsResponse;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.service.QuoteEffectiveBomCostingService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 技术补录阶段沿用正式计价 BOM 和价格检查，仅准备计价对象，不核算、采用月价或发布资料。 */
@Service
public class TechnicalDataBomPriceCheck {
  private static final Logger log = LoggerFactory.getLogger(TechnicalDataBomPriceCheck.class);
  private static final Set<String> INPUTS = Set.of("MANUFACTURING", "PACKAGE", "SOLDER", "PRICE");
  private final QuoteTechnicalDataRepository repository;
  private final QuoteTechModuleMapper modules;
  private final QuoteEffectiveBomCostingService bom;
  private final TechnicalDataPriceRequirements requirements;
  private final TechnicalDataPriceApplicationService prices;
  private final TechnicalDataVersionContentCodec content;
  private final TechnicalDataOaRecipientRepository recipients;
  private final TransactionTemplate transaction;

  public record Result(boolean checked, int missing, int shared, List<String> issues) {}

  public TechnicalDataBomPriceCheck(QuoteTechnicalDataRepository repository, QuoteTechModuleMapper modules,
      QuoteEffectiveBomCostingService bom, TechnicalDataPriceRequirements requirements,
      TechnicalDataPriceApplicationService prices, TechnicalDataVersionContentCodec content,
      TechnicalDataOaRecipientRepository recipients, PlatformTransactionManager manager) {
    this.repository = repository; this.modules = modules; this.bom = bom; this.requirements = requirements;
    this.prices = prices; this.content = content; this.recipients = recipients;
    this.transaction = new TransactionTemplate(manager);
  }

  public void afterSaved(Long productId, TechnicalDataActor actor) {
    try {
      var ready = transaction.execute(status -> {
        scope(productId, actor);
        return repository.lockModules(productId).stream().filter(row -> "MANUFACTURING".equals(row.getModuleType()))
            .allMatch(row -> !Integer.valueOf(1).equals(row.getRequiredFlag()) || "READY".equals(row.getModuleStatus()));
      });
      if (Boolean.TRUE.equals(ready)) check(productId, actor);
    } catch (RuntimeException exception) {
      // 保存已提交，检查/审批并发不能误报为保存失败。
      log.warn("saved technical materials; price check requires retry: product={}", productId, exception);
    }
  }

  public Result check(Long productId, TechnicalDataActor actor) {
    // 先验证权限；异常处理不能把越权访问变为状态写入。
    transaction.executeWithoutResult(status -> scope(productId, actor));
    try {
      return transaction.execute(status -> {
        var scope = scope(productId, actor);
        bom.prepareTechnical(scope.task().getOaNo(), scope.product().getOaFormItemId(), scope.product().getAccountingMonth());
        var checked = requirements.checkComposedBom(scope.task(), scope.product());
        update(scope, checked, actor);
        // 已由同单其他产品填写的价格只保存引用，技术员无需再填一次。
        prices.prepareSharedReference(scope.task().getId(), actor);
        return new Result(checked.issues().isEmpty(), (int) checked.items().stream().filter(row -> row.requiresEntry()).count(),
            (int) checked.items().stream().filter(row -> "SHARED".equals(row.status())).count(), checked.issues());
      });
    } catch (TechnicalDataTaskException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      log.warn("technical BOM price check failed: product={}", productId, exception);
      String message = exception instanceof com.sanhua.marketingcost.service.ingest.QuoteIngestException
          || exception instanceof com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException
          || exception instanceof com.sanhua.marketingcost.service.EffectiveTechnicalDataException
          || exception instanceof IllegalArgumentException
          ? exception.getMessage() : "计价物料检查失败，请重试；已填资料保留";
      var checked = new TechnicalDataPriceRequirementsResponse(List.of(), List.of(message), "FAILED");
      transaction.executeWithoutResult(status -> update(scope(productId, actor), checked, actor));
      return new Result(false, 0, 0, checked.issues());
    }
  }

  private Scope scope(Long id, TechnicalDataActor actor) {
    var found = repository.findProduct(id).orElseThrow(() -> invalid("补录产品不存在"));
    var task = repository.lockTask(found.getTaskId()).orElseThrow();
    var product = repository.lockProduct(id).orElseThrow();
    var all = repository.lockModules(id);
    var price = all.stream().filter(row -> "PRICE".equals(row.getModuleType())).findFirst().orElseThrow();
    if (actor == null || !Integer.valueOf(1).equals(product.getActiveFlag())
        || all.stream().noneMatch(row -> INPUTS.contains(row.getModuleType()) && actor.canEditModule(task, row)))
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, "只能检查本人正在补录的产品");
    if (!Set.of("PENDING", "NOT_REQUIRED", "EDITING", "READY", "RETURNED").contains(price.getModuleStatus()))
      throw invalid("价格资料已提交，不能改变本次审批范围");
    return new Scope(task, product, price);
  }

  private void update(Scope scope, TechnicalDataPriceRequirementsResponse checked, TechnicalDataActor actor) {
    var price = scope.price();
    boolean shared = checked.items().stream().anyMatch(row -> "SHARED".equals(row.status()));
    boolean assigned = checked.items().stream().anyMatch(row -> "ASSIGNED".equals(row.status()));
    long missing = checked.items().stream().filter(row -> row.requiresEntry()).count();
    boolean failed = !checked.issues().isEmpty();
    boolean required = !failed && (missing > 0 || shared || assigned);
    boolean otherOwner = price.getAssigneeUserId() != null && !Objects.equals(price.getAssigneeUserId(), actor.userId());
    // 已有价格负责人继续办理；不会被保存制造件的其他人员抢走。
    Long user = otherOwner ? price.getAssigneeUserId() : actor.canFillUnassigned(scope.task()) ? null : actor.userId();
    String name = otherOwner ? price.getAssigneeName() : user == null ? null : actor.name();
    if (!otherOwner && scope.task().getOaAssignmentVersion() != null && scope.task().getOaAssignmentVersion() > 0)
      recipients.manufacturingPriceScope(scope.task().getId(), actor.userId(), required);
    String availability = failed ? "ERROR" : required ? "MISSING" : "AVAILABLE";
    String reason = failed ? "TECHNICAL_BOM_PRICE_FAILED" : shared && missing == 0 && !assigned
        ? "TECHNICAL_BOM_PRICE_SHARED" : "TECHNICAL_BOM_PRICE_CHECKED";
    String message = failed ? String.join("；", checked.issues()) : missing > 0 ? "实际计价物料中有 " + missing + " 项需补价"
        : assigned ? "缺价已由原任务办理，请在原任务补齐，无需重复填写"
        : shared ? "已沿用其他产品填好的价格，无需重复补录" : "实际计价物料已有可用价格，无需补录";
    String status = failed ? "PENDING" : !required ? "NOT_REQUIRED" : !failed && !assigned && complete(price, checked) ? "READY"
        : price.getCurrentVersionId() == null ? "PENDING" : "EDITING";
    if (modules.refreshBomPrice(price.getId(), price.getRowVersion(), required, status, reason, message,
        availability, checked.fingerprint(), LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE), user, name) != 1)
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "价格检查期间资料已变化，请重试");
  }

  private boolean complete(QuoteTechModule price, TechnicalDataPriceRequirementsResponse checked) {
    if (price.getCurrentVersionId() == null) return false;
    var version = repository.findVersion(price.getCurrentVersionId()).orElseThrow();
    if (!Objects.equals(version.getProductId(), price.getProductId()) || !"DRAFT".equals(version.getVersionStatus())) return false;
    var saved = content.prices(version);
    return saved != null && saved.items() != null && checked.items().stream().filter(row -> row.requiresEntry())
        .allMatch(row -> saved.items().stream().anyMatch(item -> row.itemKey().equals(item.itemKey()) && TechnicalDataPriceRules.validate(item).isEmpty()));
  }

  private record Scope(QuoteTechTask task, QuoteTechProduct product, QuoteTechModule price) {}
  private static TechnicalDataTaskException invalid(String text) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST, text);
  }
}
