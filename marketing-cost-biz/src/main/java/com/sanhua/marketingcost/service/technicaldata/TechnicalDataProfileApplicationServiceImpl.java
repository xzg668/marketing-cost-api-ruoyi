package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import java.math.BigDecimal;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ProductFees;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TechnicalDataProfileApplicationServiceImpl
    implements TechnicalDataProfileApplicationService {

  private final TechnicalDataSharedModules sharedModules;
  private final TechnicalDataProfileRepository repository;
  private final TechnicalDataSourceSnapshotFactory snapshotFactory;
  private final TechnicalDataVersionContentCodec contentCodec;

  public TechnicalDataProfileApplicationServiceImpl(
      TechnicalDataProfileRepository repository, TechnicalDataSourceSnapshotFactory snapshotFactory,
      TechnicalDataVersionContentCodec contentCodec, TechnicalDataSharedModules sharedModules) {
    this.sharedModules = sharedModules;
    this.repository = repository;
    this.snapshotFactory = snapshotFactory;
    this.contentCodec = contentCodec;
  }

  @Override
  @Transactional
  public TechnicalDataProfileResponse save(
      Long productId,
      TechnicalDataProfileUpdateRequest request,
      TechnicalDataActor actor) {
    requireActor(actor);
    Command command = normalize(productId, request);

    QuoteTechProduct found = repository.findProduct(command.productId())
        .orElseThrow(() -> error(
            TechnicalDataTaskErrorCode.PRODUCT_NOT_FOUND, "技术资料产品不存在"));
    QuoteTechTask task = repository.lockTask(found.getTaskId())
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    QuoteTechProduct product = repository.lockProduct(command.productId()).orElseThrow();
    if (product.getCurrentEditVersionId() != null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    requireEditable(task, product, actor);
    if (!Integer.valueOf(2).equals(product.getContentSchemaVersion())) {
      throw forbidden("历史补录版本仅可查看，请通过当前产品核算检查进入新补录流程");
    }
    if (!Objects.equals(product.getRowVersion(), command.expectedVersion())) {
      throw conflict(product.getRowVersion());
    }

    QuoteTechModule profileModule = repository.lockProfileModule(product.getId())
        .orElseThrow(() -> error(
            TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT, "产品缺少PROFILE模块"));
    if (!actor.canEditModule(task, profileModule)) {
      throw forbidden("当前产品资料模块未分派给本人，或 OA 分派尚未确认");
    }
    sharedModules.requireOwnership(productId, "PROFILE");
    LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    QuoteTechDataVersion draft = product.getCurrentEditVersionId() == null
        ? createDraft(product, command, actor, now)
        : updateDraft(product, command, actor, now);

    if (repository.updateProfileModule(
        profileModule.getId(), draft.getId(), profileModule.getRowVersion(), now) != 1) {
      throw conflict(command.expectedVersion());
    }
    if (repository.updateProductProfilePointer(
        product.getId(), draft.getId(), command.expectedVersion(), now) != 1) {
      throw conflict(command.expectedVersion());
    }
    if ("PENDING".equals(task.getTaskStatus())) {
      repository.markTaskInProgress(task.getId(), actor.userId(), now);
    }
    return response(product, draft, command.expectedVersion() + 1, now);
  }

  private QuoteTechDataVersion createDraft(
      QuoteTechProduct product,
      Command command,
      TechnicalDataActor actor,
      LocalDateTime now) {
    QuoteTechDataVersion draft = new QuoteTechDataVersion();
    draft.setProductId(product.getId());
    draft.setContentSchemaVersion(product.getContentSchemaVersion() == null
        ? 1 : product.getContentSchemaVersion());
    draft.setVersionNo(1);
    draft.setVersionStatus(QuoteTechDataVersion.STATUS_DRAFT);
    draft.setProductModel(snapshotFactory.readProfile(product.getSourceSnapshotJson()).productModel());
    draft.setProductProperty(command.productProperty());
    draft.setNewProductFlag(Boolean.TRUE.equals(snapshotFactory.readProfile(product.getSourceSnapshotJson()).newProduct()) ? 1 : 0);
    draft.setProductFeesJson(contentCodec.productFeesJson(command.fees()));
    draft.setPackageTotalAmount(BigDecimal.ZERO);
    draft.setAuxiliaryTotalAmount(BigDecimal.ZERO);
    draft.setSalaryTotalAmount(BigDecimal.ZERO);
    draft.setRowVersion(0);
    draft.setCreatedBy(actor.userId());
    draft.setUpdatedBy(actor.userId());
    draft.setCreatedAt(now);
    draft.setUpdatedAt(now);
    return repository.insertVersion(draft);
  }

  private QuoteTechDataVersion updateDraft(
      QuoteTechProduct product,
      Command command,
      TechnicalDataActor actor,
      LocalDateTime now) {
    QuoteTechDataVersion draft = repository.lockVersion(product.getCurrentEditVersionId())
        .orElseThrow(() -> error(
            TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT, "产品当前编辑版本不存在"));
    if (!Objects.equals(draft.getProductId(), product.getId())) {
      throw error(TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT, "产品当前编辑版本归属错误");
    }
    if (!QuoteTechDataVersion.STATUS_DRAFT.equals(draft.getVersionStatus())) {
      throw error(
          TechnicalDataTaskErrorCode.VERSION_CONFLICT,
          "当前版本已" + draft.getVersionStatus() + "，不能继续修改");
    }
    int draftVersion = draft.getRowVersion();
    draft.setProductModel(snapshotFactory.readProfile(product.getSourceSnapshotJson()).productModel());
    draft.setProductProperty(command.productProperty());
    draft.setNewProductFlag(Boolean.TRUE.equals(snapshotFactory.readProfile(product.getSourceSnapshotJson()).newProduct()) ? 1 : 0);
    draft.setProductFeesJson(contentCodec.productFeesJson(command.fees()));
    draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftProfile(draft, draftVersion, now) != 1) {
      throw conflict(product.getRowVersion());
    }
    draft.setRowVersion(draftVersion + 1);
    draft.setUpdatedAt(now);
    return draft;
  }

  private void requireEditable(
      QuoteTechTask task, QuoteTechProduct product, TechnicalDataActor actor) {
    if (!actor.canAccessTask(task.getId())) {
      throw forbidden("短时访问会话不允许跨任务操作");
    }
    if (!Objects.equals(task.getActiveFlag(), 1) || !Objects.equals(product.getActiveFlag(), 1)) {
      throw forbidden("历史任务只能查看，不能修改");
    }

  }

  private Command normalize(Long productId, TechnicalDataProfileUpdateRequest request) {
    if (productId == null || productId <= 0) throw invalid("productId必须大于0");
    if (request == null) throw invalid("请求体不能为空");
    if (!request.getUnknownFields().isEmpty()) {
      throw invalid("OA只读字段或未知字段不能修改："
          + String.join(",", request.getUnknownFields().keySet()));
    }
    String property = request.getProductProperty();
    if (!"标准品".equals(property) && !"非标品".equals(property)) {
      throw invalid("产品属性只能为标准品或非标品");
    }
    ProductFees fees;
    try {
      fees = TechnicalDataProductFeeRules.parse(request.getHasAdditionalFees(),
          request.getUnitToolingFee(), request.getUnitMouldFee(), request.getUnitCertificationFee());
    } catch (IllegalArgumentException exception) {
      throw invalid(exception.getMessage());
    }
    Integer expectedVersion = request.getExpectedVersion();
    if (expectedVersion == null || expectedVersion < 0) {
      throw invalid("expectedVersion必须大于等于0");
    }
    return new Command(
        productId, property, fees, expectedVersion);
  }

  private TechnicalDataProfileResponse response(
      QuoteTechProduct product, QuoteTechDataVersion version, int expectedVersion, LocalDateTime updatedAt) {
    ProductFees fees = contentCodec.productFees(version);
    return new TechnicalDataProfileResponse(
        version.getId(), version.getVersionNo(), version.getVersionStatus(),
        version.getProductModel(), version.getProductProperty(),
        snapshotFactory.readProfile(product.getSourceSnapshotJson()).newProduct(),
        fees.includesNewToolingMouldCertificationFee(), TechnicalDataProductFeeRules.display(fees.unitToolingFee()),
        TechnicalDataProductFeeRules.display(fees.unitMouldFee()), TechnicalDataProductFeeRules.display(fees.unitCertificationFee()), expectedVersion,
        version.getRowVersion(), updatedAt);
  }

  private void requireActor(TechnicalDataActor actor) {
    if (actor == null || actor.userId() == null || actor.userId() <= 0) {
      throw forbidden("当前登录用户无效");
    }
  }

  private TechnicalDataTaskException conflict(int currentVersion) {
    return error(
        TechnicalDataTaskErrorCode.VERSION_CONFLICT,
        "数据已被其他会话修改，请刷新后重试；当前版本=" + currentVersion);
  }

  private TechnicalDataTaskException invalid(String message) {
    return error(TechnicalDataTaskErrorCode.INVALID_REQUEST, message);
  }

  private TechnicalDataTaskException forbidden(String message) {
    return error(TechnicalDataTaskErrorCode.FORBIDDEN, message);
  }

  private TechnicalDataTaskException error(
      TechnicalDataTaskErrorCode code, String message) {
    return new TechnicalDataTaskException(code, message);
  }

  private record Command(
      Long productId,
      String productProperty,
      ProductFees fees,
      int expectedVersion) {}
}
