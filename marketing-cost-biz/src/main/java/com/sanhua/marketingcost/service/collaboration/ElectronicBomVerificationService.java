package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.dto.collaboration.ElectronicBomVerificationResponse;
import com.sanhua.marketingcost.dto.collaboration.ElectronicBomVerificationResponse.Issue;
import com.sanhua.marketingcost.dto.collaboration.ElectronicBomVerifyRequest;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.integration.drawing.ElectronicBomFetchResult;
import com.sanhua.marketingcost.integration.drawing.ElectronicBomQuery;
import com.sanhua.marketingcost.integration.drawing.ElectronicDrawingBomGateway;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.collaboration.ElectronicBomStructureValidator.ValidationResult;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** QCBP-11 回取编排：远程查询、独立校验、原子落库，再调用现有价格检查。 */
@Service
public class ElectronicBomVerificationService {

  private static final String DEFAULT_BOM_PURPOSE = "主制造";

  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationCurrentPrincipalProvider principalProvider;
  private final TechnicalBomDraftApplicationService draftService;
  private final ElectronicDrawingBomGateway gateway;
  private final ElectronicBomStructureValidator validator;
  private final ElectronicBomVerificationPersistenceService persistence;
  private final TechnicalRealPriceGapScanService priceScanService;

  public ElectronicBomVerificationService(
      QuoteCollaborationTaskRepository repository,
      CollaborationCurrentPrincipalProvider principalProvider,
      TechnicalBomDraftApplicationService draftService,
      ElectronicDrawingBomGateway gateway,
      ElectronicBomStructureValidator validator,
      ElectronicBomVerificationPersistenceService persistence,
      TechnicalRealPriceGapScanService priceScanService) {
    this.repository = repository;
    this.principalProvider = principalProvider;
    this.draftService = draftService;
    this.gateway = gateway;
    this.validator = validator;
    this.persistence = persistence;
    this.priceScanService = priceScanService;
  }

  public ElectronicBomVerificationResponse verify(
      Long taskId, ElectronicBomVerifyRequest request) {
    Integer expectedVersion = request == null ? null : request.expectedVersion();
    if (expectedVersion == null || expectedVersion <= 0) {
      throw new IllegalArgumentException("expectedVersion不能为空");
    }
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    requireVersion(task, expectedVersion);
    // 导出门禁与回取门禁使用同一份草稿，避免页面可导出而后端校验另一份数据。
    draftService.exportSnapshot(taskId);
    CollaborationScope scope = new CollaborationScope(
        task.getBusinessUnitType(), task.getApplicableOrgCode());
    QuoteCollaborationQuoteLink owner = owner(task, scope);
    String purpose = firstText(request == null ? null : request.bomPurpose(), DEFAULT_BOM_PURPOSE);
    LocalDate asOfDate = LocalDate.now();

    if (!StringUtils.hasText(task.getProductCode())) {
      return fail(task, expectedVersion, principal, scope, List.of(
          new ElectronicBomValidationIssue(null, null, "TARGET_PRODUCT_MAPPING_REQUIRED",
              "当前新品还没有电子图库正式料号，请先完成目标料号映射")));
    }
    ElectronicBomFetchResult fetched = gateway.fetchCurrentBom(new ElectronicBomQuery(
        task.getProductCode().trim(), materialOrganization(task), task.getPriceOrgCode(),
        purpose, asOfDate, UUID.randomUUID().toString()));
    if (fetched == null || fetched.status() != ElectronicBomFetchResult.Status.FOUND) {
      ElectronicBomValidationIssue issue = fetchIssue(fetched);
      if (externalFailure(fetched)) {
        return new ElectronicBomVerificationResponse(false, "ELECTRONIC_BOM_UNAVAILABLE",
            issue.message(), task.getTaskVersion(), task.getElectronicBomFingerprint(), null,
            0, "NOT_CHECKED", 0, List.of(responseIssue(issue)));
      }
      return fail(task, expectedVersion, principal, scope, List.of(issue));
    }
    ValidationResult validation = validator.validate(fetched, task.getProductCode(),
        materialOrganization(task), purpose, asOfDate);
    if (!validation.passed()) {
      return fail(task, expectedVersion, principal, scope, validation.issues());
    }

    var verified = persistence.persistVerifiedBom(task.getId(), expectedVersion, principal,
        scope, validation.bom());
    CollaborationPriceScanResult priceScan = priceScanService.scan(verified.task(), owner);
    if (priceScan == null || priceScan.status() == CollaborationPriceScanResult.Status.ERROR) {
      String message = firstText(priceScan == null ? null : priceScan.message(),
          "电子图库BOM已校验，但价格检查暂时失败，请稍后重新回取校验");
      return new ElectronicBomVerificationResponse(true, "BOM_VERIFIED_PRICE_CHECK_FAILED",
          message, verified.task().getTaskVersion(), verified.fingerprint(),
          validation.bom().sourceVersion(), verified.nodeCount(), "ERROR", 0,
          List.of(new Issue(null, null, "PRICE_SCAN_FAILED", message)));
    }
    var scanSaved = persistence.persistPriceScan(task.getId(), verified.task().getTaskVersion(),
        principal, scope, priceScan);
    boolean hasGaps = scanSaved.gapCount() > 0;
    return new ElectronicBomVerificationResponse(true,
        hasGaps ? "VERIFIED_WITH_PRICE_GAPS" : "VERIFIED_READY",
        hasGaps
            ? "电子图库BOM已回取校验，已进入底层物料补价"
            : "电子图库BOM已回取校验，当前没有真实缺价",
        scanSaved.task().getTaskVersion(), verified.fingerprint(),
        validation.bom().sourceVersion(), verified.nodeCount(), priceScan.status().name(),
        scanSaved.gapCount(), List.of());
  }

  private ElectronicBomVerificationResponse fail(
      QuoteCollaborationProductTask task,
      Integer expectedVersion,
      CollaborationPrincipal principal,
      CollaborationScope scope,
      List<ElectronicBomValidationIssue> issues) {
    var failed = persistence.persistFailure(task.getId(), expectedVersion, principal, scope, issues);
    return new ElectronicBomVerificationResponse(false, "VALIDATION_FAILED",
        "电子图库BOM校验未通过，请按问题修改后重新校验",
        failed.task().getTaskVersion(), failed.task().getElectronicBomFingerprint(), null,
        0, "NOT_CHECKED", 0, issues.stream().map(this::responseIssue).toList());
  }

  private ElectronicBomValidationIssue fetchIssue(ElectronicBomFetchResult result) {
    ElectronicBomFetchResult.Status status = result == null
        ? ElectronicBomFetchResult.Status.UPSTREAM_ERROR : result.status();
    String code = switch (status) {
      case NOT_FOUND -> "ELECTRONIC_BOM_NOT_FOUND";
      case TIMEOUT -> "ELECTRONIC_BOM_TIMEOUT";
      case FORBIDDEN -> "ELECTRONIC_BOM_FORBIDDEN";
      case VOID -> "ELECTRONIC_BOM_VERSION_VOID";
      case INTEGRATION_DISABLED -> "ELECTRONIC_BOM_INTEGRATION_DISABLED";
      default -> "ELECTRONIC_BOM_UPSTREAM_ERROR";
    };
    return new ElectronicBomValidationIssue(null, null, code,
        firstText(result == null ? null : result.message(), "电子图库BOM查询失败"));
  }

  private static boolean externalFailure(ElectronicBomFetchResult result) {
    ElectronicBomFetchResult.Status status = result == null
        ? ElectronicBomFetchResult.Status.UPSTREAM_ERROR : result.status();
    return status == ElectronicBomFetchResult.Status.TIMEOUT
        || status == ElectronicBomFetchResult.Status.FORBIDDEN
        || status == ElectronicBomFetchResult.Status.INTEGRATION_DISABLED
        || status == ElectronicBomFetchResult.Status.UPSTREAM_ERROR;
  }

  private Issue responseIssue(ElectronicBomValidationIssue issue) {
    return new Issue(issue.nodeKey(), issue.bomPath(), issue.code(), issue.message());
  }

  private QuoteCollaborationQuoteLink owner(
      QuoteCollaborationProductTask task, CollaborationScope scope) {
    return repository.findLinksByProductTask(task.getId(), scope).stream()
        .filter(link -> "OWNER".equals(link.getLinkType()))
        .max(Comparator.comparing(QuoteCollaborationQuoteLink::getId))
        .orElseThrow(() -> invalid("产品任务缺少报价来源关联"));
  }

  private QuoteCollaborationProductTask ownTask(
      Long taskId, CollaborationPrincipal principal) {
    if (taskId == null || taskId <= 0) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_NOT_FOUND, "技术任务不存在");
    }
    return repository.findMineById(taskId, principal.userId(), currentBusinessUnit())
        .orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND, "技术任务不存在"));
  }

  private static String materialOrganization(QuoteCollaborationProductTask task) {
    if (StringUtils.hasText(task.getMaterialOrgCode())) {
      return MaterialOrganization.normalize(task.getMaterialOrgCode());
    }
    return MaterialOrganization.fromPriceOrgCode(task.getPriceOrgCode()).getCode();
  }

  private static void requireVersion(
      QuoteCollaborationProductTask task, Integer expectedVersion) {
    if (!Objects.equals(task.getTaskVersion(), expectedVersion)) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_VERSION_CONFLICT, "任务版本已变化，请刷新后重试");
    }
  }

  private static String currentBusinessUnit() {
    return CollaborationScope.requireBusinessUnit(BusinessUnitContext.getCurrentBusinessUnitType());
  }

  private static String firstText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private static CollaborationDomainException invalid(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, message);
  }
}
