package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataProfileApplicationServiceImpl
    implements TechnicalDataProfileApplicationService {
  private static final Set<String> EDITABLE_TASK_STATUSES = Set.of(
      "PENDING", "IN_PROGRESS", "PARTIALLY_RETURNED");
  private static final Set<String> PRODUCT_PROPERTIES = Set.of("标准品", "非标品");

  private final TechnicalDataProfileRepository repository;

  public TechnicalDataProfileApplicationServiceImpl(TechnicalDataProfileRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public TechnicalDataProfileResponse save(
      Long productId,
      TechnicalDataProfileUpdateRequest request,
      TechnicalDataActor actor) {
    requireActor(actor);
    if (!actor.canEdit()) throw forbidden("当前用户无权编辑产品技术资料");
    Command command = normalize(productId, request);

    QuoteTechProduct product = repository.lockProduct(command.productId())
        .orElseThrow(() -> error(
            TechnicalDataTaskErrorCode.PRODUCT_NOT_FOUND, "技术资料产品不存在"));
    QuoteTechTask task = repository.lockTask(product.getTaskId())
        .orElseThrow(() -> error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    requireEditable(task, product, actor);
    if (!Objects.equals(product.getRowVersion(), command.expectedVersion())) {
      throw conflict(product.getRowVersion());
    }

    QuoteTechModule profileModule = repository.lockProfileModule(product.getId())
        .orElseThrow(() -> error(
            TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT, "产品缺少PROFILE模块"));
    if ("PARTIALLY_RETURNED".equals(task.getTaskStatus())
        && !Set.of("RETURNED", "EDITING").contains(profileModule.getModuleStatus())) {
      throw error(
          TechnicalDataTaskErrorCode.VERSION_CONFLICT,
          "产品基本信息本轮未退回，继续展示V1且禁止修改");
    }
    LocalDateTime now = LocalDateTime.now();
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
    return response(draft, command.expectedVersion() + 1, now);
  }

  private QuoteTechDataVersion createDraft(
      QuoteTechProduct product,
      Command command,
      TechnicalDataActor actor,
      LocalDateTime now) {
    QuoteTechDataVersion draft = new QuoteTechDataVersion();
    draft.setProductId(product.getId());
    draft.setVersionNo(1);
    draft.setVersionStatus(QuoteTechDataVersion.STATUS_DRAFT);
    draft.setProductModel(command.productModel());
    draft.setProductProperty(command.productProperty());
    draft.setNewProductFlag(command.newProduct() ? 1 : 0);
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
    draft.setProductModel(command.productModel());
    draft.setProductProperty(command.productProperty());
    draft.setNewProductFlag(command.newProduct() ? 1 : 0);
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
    if (!actor.admin() && !Objects.equals(task.getAssigneeUserId(), actor.userId())) {
      throw forbidden("只能修改本人负责的技术资料产品");
    }
    if (!EDITABLE_TASK_STATUSES.contains(task.getTaskStatus())) {
      throw error(
          TechnicalDataTaskErrorCode.VERSION_CONFLICT,
          "任务状态为" + task.getTaskStatus() + "，当前不可修改");
    }
  }

  private Command normalize(Long productId, TechnicalDataProfileUpdateRequest request) {
    if (productId == null || productId <= 0) throw invalid("productId必须大于0");
    if (request == null) throw invalid("请求体不能为空");
    if (!request.getUnknownFields().isEmpty()) {
      throw invalid("OA只读字段或未知字段不能修改："
          + String.join(",", request.getUnknownFields().keySet()));
    }
    String model = request.getProductModel();
    if (!StringUtils.hasText(model)) throw invalid("产品型号不能为空");
    model = model.trim();
    if (model.length() > 255) throw invalid("产品型号长度不能超过255");
    String property = request.getProductProperty();
    if (!PRODUCT_PROPERTIES.contains(property)) {
      throw invalid("产品属性只能为标准品或非标品");
    }
    if (request.getNewProduct() == null) throw invalid("新品只能为是或否");
    Integer expectedVersion = request.getExpectedVersion();
    if (expectedVersion == null || expectedVersion < 0) {
      throw invalid("expectedVersion必须大于等于0");
    }
    return new Command(
        productId, model, property, request.getNewProduct(), expectedVersion);
  }

  private TechnicalDataProfileResponse response(
      QuoteTechDataVersion version, int expectedVersion, LocalDateTime updatedAt) {
    return new TechnicalDataProfileResponse(
        version.getId(), version.getVersionNo(), version.getVersionStatus(),
        version.getProductModel(), version.getProductProperty(),
        Objects.equals(version.getNewProductFlag(), 1), expectedVersion,
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
      String productModel,
      String productProperty,
      boolean newProduct,
      int expectedVersion) {}
}
