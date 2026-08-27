package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.dto.quotebom.SupplementBomReadResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import com.sanhua.marketingcost.service.SupplementBomReadService;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteProductBomPreparationServiceImpl implements QuoteProductBomPreparationService {

  static final String PREPARATION_READY = "READY";
  static final String PREPARATION_NEED_TECH = "NEED_TECH";
  static final String PREPARATION_ERROR = "ERROR";
  static final String REVIEW_NOT_SUBMITTED = "NOT_SUBMITTED";
  static final String BODY_SOURCE_FORMAL = "FORMAL_BOM";
  static final String BODY_SOURCE_MANUAL = "MANUAL_SUPPLEMENT";
  static final String BODY_SOURCE_MONTHLY_LOCK = "MONTHLY_LOCK";
  static final String REUSE_TYPE_MANUAL_BOM = "MANUAL_BOM";
  static final String REUSE_TYPE_PACKAGE_REFERENCE = "PACKAGE_REFERENCE";
  static final String SCOPE_NON_BARE_FULL_BOM = "NON_BARE_FULL_BOM";
  static final String SCOPE_BARE_BODY_BOM = "BARE_BODY_BOM";
  static final String SCOPE_PACKAGE_REFERENCE = "PACKAGE_REFERENCE";
  static final int ACTIVE = 1;

  private final OaFormItemMapper oaFormItemMapper;
  private final OaFormMapper oaFormMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final QuoteProductTypeResolveService productTypeResolveService;
  private final FormalBomReadService formalBomReadService;
  private final SupplementBomReadService supplementBomReadService;
  private final PackageComponentStructureReadService packageComponentStructureReadService;
  private final QuoteBomContextResolver quoteBomContextResolver;

  public QuoteProductBomPreparationServiceImpl(
      OaFormItemMapper oaFormItemMapper,
      OaFormMapper oaFormMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteProductTypeResolveService productTypeResolveService,
      FormalBomReadService formalBomReadService,
      SupplementBomReadService supplementBomReadService,
      PackageComponentStructureReadService packageComponentStructureReadService,
      QuoteBomContextResolver quoteBomContextResolver) {
    this.oaFormItemMapper = oaFormItemMapper;
    this.oaFormMapper = oaFormMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.preparationRecordMapper = preparationRecordMapper;
    this.productTypeResolveService = productTypeResolveService;
    this.formalBomReadService = formalBomReadService;
    this.supplementBomReadService = supplementBomReadService;
    this.packageComponentStructureReadService = packageComponentStructureReadService;
    this.quoteBomContextResolver = quoteBomContextResolver;
  }

  @Override
  @Transactional
  public QuoteProductBomPreparationPreview prepareByOaFormItem(Long itemId) {
    return prepareByOaFormItem(itemId, LocalDate.now());
  }

  @Override
  @Transactional
  public QuoteProductBomPreparationPreview prepareByOaFormItem(Long itemId, LocalDate quoteDate) {
    QuoteContext context = loadContext(itemId);
    QuoteBomContext bomContext = resolvePreparationBomContext(context);
    String productCode = bomContext.productCode();
    String periodMonth = bomContext.costPeriodMonth();
    QuoteDataOrganization organization = bomContext.organization();
    QuoteProductTypeResolveResult typeResult =
        resolveProductType(
            context.item(), productCode, organization.materialOrganizationCode());
    if (typeResult.productType() == QuoteProductType.DATA_MISSING
        || typeResult.productType() == QuoteProductType.UNKNOWN) {
      String error = firstText(typeResult.errorMessage(), "产品形态无法判断");
      QuoteBomStatus status = upsertStatus(context, productCode, periodMonth, typeResult, null, error);
      QuoteBomPreparationRecord record =
          upsertPreparationRecord(
              context,
              status,
              typeResult,
              periodMonth,
              PREPARATION_ERROR,
              false,
              null,
              null,
              null,
              null,
              organization,
              error);
      return toPreview(
          record,
          status,
          false,
          false,
          true,
          null,
          false,
          List.of(),
          null,
          false,
          List.of(),
          List.of("PRODUCT_TYPE"),
          List.of(error));
    }

    QuoteBomPreparationRecord locked = findMonthlyLockedRecord(productCode, periodMonth, organization);
    if (locked != null && Objects.equals(locked.getOaFormItemId(), itemId)) {
      QuoteBomStatus status =
          locked.getQuoteBomStatusId() == null
              ? null
              : quoteBomStatusMapper.selectById(locked.getQuoteBomStatusId());
      return toPreview(
          locked,
          status,
          true,
          false,
          false,
          BODY_SOURCE_MONTHLY_LOCK,
          true,
          List.of(),
          locked.getReferenceFinishedCode(),
          trimToNull(locked.getReferenceFinishedCode()) != null,
          List.of(),
          List.of(),
          List.of());
    }
    if (locked != null) {
      QuoteBomStatus status =
          upsertStatus(context, productCode, periodMonth, typeResult, locked.getReferenceFinishedCode(), null);
      QuoteBomPreparationRecord record =
          upsertPreparationRecord(
              context,
              status,
              typeResult,
              periodMonth,
              PREPARATION_READY,
              QuoteProductType.BARE == typeResult.productType(),
              locked.getReferenceFinishedCode(),
              locked.getSourceTopProductCode(),
              firstText(locked.getReuseType(), BODY_SOURCE_MONTHLY_LOCK),
              locked,
              organization,
              null);
      return toPreview(
          record,
          status,
          true,
          false,
          false,
          BODY_SOURCE_MONTHLY_LOCK,
          true,
          List.of(),
          record.getReferenceFinishedCode(),
          record.getReferenceFinishedCode() != null,
          List.of(),
          List.of(),
          List.of());
    }

    if (typeResult.productType() == QuoteProductType.NON_BARE) {
      return prepareNonBare(context, typeResult, periodMonth, organization, resolveQuoteDate(quoteDate));
    }
    return prepareBare(context, typeResult, periodMonth, organization, resolveQuoteDate(quoteDate));
  }

  private QuoteProductBomPreparationPreview prepareNonBare(
      QuoteContext context,
      QuoteProductTypeResolveResult typeResult,
      String periodMonth,
      QuoteDataOrganization organization,
      LocalDate quoteDate) {
    String productCode = typeResult.quoteProductCode();
    FormalBomReadResult formal =
        readFormalBom(productCode, periodMonth, quoteDate, organization);
    if (formal.found()) {
      QuoteBomStatus status = upsertStatus(context, productCode, periodMonth, typeResult, null, null);
      QuoteBomPreparationRecord record =
          upsertPreparationRecord(
              context,
              status,
              typeResult,
              periodMonth,
              PREPARATION_READY,
              false,
              null,
              null,
              null,
              null,
              organization,
              null);
      return toPreview(
          record,
          status,
          true,
          false,
          false,
          BODY_SOURCE_FORMAL,
          true,
          formal.lines(),
          null,
          false,
          List.of(),
          List.of(),
          List.of());
    }

    SupplementBomReadResult supplement =
        supplementBomReadService.readApproved(
            productCode, QuoteProductType.NON_BARE.getCode(), SCOPE_NON_BARE_FULL_BOM, periodMonth);
    if (supplement.found()) {
      QuoteBomStatus status = upsertStatus(context, productCode, periodMonth, typeResult, null, null);
      status.setBomStatus(QuoteBomStatusCode.REUSED_CURRENT_MONTH.getCode());
      quoteBomStatusMapper.updateById(status);
      QuoteBomPreparationRecord record =
          upsertPreparationRecord(
              context,
              status,
              typeResult,
              periodMonth,
              PREPARATION_READY,
              false,
              null,
              null,
              REUSE_TYPE_MANUAL_BOM,
              supplement,
              organization,
              null);
      return toPreview(
          record,
          status,
          true,
          false,
          false,
          BODY_SOURCE_MANUAL,
          true,
          supplement.lines(),
          null,
          false,
          List.of(),
          List.of(),
          List.of());
    }

    List<String> gaps = compact(formal.gapMessage(), supplement.gapMessage(), "非裸品完整 BOM 缺失，需技术员补录");
    QuoteBomStatus status = upsertStatus(context, productCode, periodMonth, typeResult, null, String.join("；", gaps));
    QuoteBomPreparationRecord record =
        upsertPreparationRecord(
            context,
            status,
            typeResult,
            periodMonth,
            PREPARATION_NEED_TECH,
            false,
            null,
            null,
            null,
            null,
            organization,
            String.join("；", gaps));
    return toPreview(
        record,
        status,
        false,
        true,
        false,
        null,
        false,
        List.of(),
        null,
        false,
        List.of(),
        List.of(SCOPE_NON_BARE_FULL_BOM),
        gaps);
  }

  private QuoteProductBomPreparationPreview prepareBare(
      QuoteContext context,
      QuoteProductTypeResolveResult typeResult,
      String periodMonth,
      QuoteDataOrganization organization,
      LocalDate quoteDate) {
    String productCode = typeResult.quoteProductCode();
    FormalBomReadResult formal =
        readFormalBom(productCode, periodMonth, quoteDate, organization);
    boolean bodyReady = formal.found();
    String bodySource = bodyReady ? BODY_SOURCE_FORMAL : null;
    List<QuoteBomSourceLineDto> bodyLines = bodyReady ? formal.lines() : List.of();
    SupplementBomReadResult supplement = null;
    if (!bodyReady) {
      supplement =
          supplementBomReadService.readApproved(
              productCode, QuoteProductType.BARE.getCode(), SCOPE_BARE_BODY_BOM, periodMonth);
      bodyReady = supplement.found();
      bodySource = bodyReady ? BODY_SOURCE_MANUAL : null;
      bodyLines = bodyReady ? supplement.lines() : List.of();
    }

    PackageComponentStructureReadResult packageResult =
        packageComponentStructureReadService.readApprovedReferenceForBareProduct(
            productCode, organization.priceOrgCode(), organization.materialOrganizationCode());
    boolean packageReady = packageResult.found();
    List<PackageComponentStructureLineDto> packageLines = packageReady ? packageResult.lines() : List.of();
    boolean ready = bodyReady && packageReady;
    List<String> missingScopes = new ArrayList<>();
    List<String> gaps = new ArrayList<>();
    if (!bodyReady) {
      missingScopes.add(SCOPE_BARE_BODY_BOM);
      gaps.add(firstText(formal.gapMessage(), "裸品本体正式 BOM 缺失"));
      if (supplement != null) {
        gaps.add(firstText(supplement.gapMessage(), "未找到可复用裸品本体补录 BOM"));
      }
    }
    if (!packageReady) {
      missingScopes.add(SCOPE_PACKAGE_REFERENCE);
      if (packageResult.gaps() == null || packageResult.gaps().isEmpty()) {
        gaps.add("裸品包装参考缺失");
      } else {
        gaps.addAll(packageResult.gaps());
      }
    }

    QuoteBomStatus status =
        upsertStatus(
            context,
            productCode,
            periodMonth,
            typeResult,
            packageResult.referenceFinishedCode(),
            ready ? null : String.join("；", gaps));
    QuoteBomPreparationRecord record =
        upsertPreparationRecord(
            context,
            status,
            typeResult,
            periodMonth,
            ready ? PREPARATION_READY : PREPARATION_NEED_TECH,
            true,
            packageResult.referenceFinishedCode(),
            packageResult.sourceTopProductCode(),
            supplement != null && supplement.found() ? REUSE_TYPE_MANUAL_BOM : null,
            supplement != null && supplement.found() ? supplement : null,
            organization,
            ready ? null : String.join("；", gaps));
    if (packageReady && record.getReuseType() == null) {
      record.setReuseType(REUSE_TYPE_PACKAGE_REFERENCE);
      preparationRecordMapper.updateById(record);
    }
    return toPreview(
        record,
        status,
        ready,
        !ready,
        false,
        bodySource,
        bodyReady,
        bodyLines,
        packageResult.referenceFinishedCode(),
        packageReady,
        packageLines,
        missingScopes,
        gaps);
  }

  private QuoteContext loadContext(Long itemId) {
    if (itemId == null) {
      throw new IllegalArgumentException("报价产品行 ID 不能为空");
    }
    OaFormItem item = oaFormItemMapper.selectById(itemId);
    if (item == null) {
      throw new IllegalArgumentException("报价产品行不存在: " + itemId);
    }
    OaForm form = oaFormMapper.selectById(item.getOaFormId());
    if (form == null) {
      throw new IllegalArgumentException("报价单不存在: " + item.getOaFormId());
    }
    return new QuoteContext(form, item);
  }

  private QuoteProductTypeResolveResult resolveProductType(
      OaFormItem item, String productCode, String organizationCode) {
    if (!QuoteProductIdentityUtils.hasFormalMaterialNo(item)) {
      return new QuoteProductTypeResolveResult(
          productCode,
          QuoteProductType.NON_BARE,
          null,
          null,
          item == null ? null : item.getProductName(),
          item == null ? null : item.getSpec(),
          null);
    }
    String organization = MaterialOrganization.normalize(organizationCode);
    return productTypeResolveService.resolve(productCode, organization);
  }

  private FormalBomReadResult readFormalBom(
      String productCode,
      String periodMonth,
      LocalDate quoteDate,
      QuoteDataOrganization organization) {
    return formalBomReadService.read(productCode, periodMonth, null, quoteDate, organization);
  }

  private QuoteBomStatus upsertStatus(
      QuoteContext context,
      String productCode,
      String periodMonth,
      QuoteProductTypeResolveResult typeResult,
      String referenceFinishedCode,
      String errorMessage) {
    QuoteBomStatus status =
        quoteBomStatusMapper.selectOne(
            Wrappers.<QuoteBomStatus>lambdaQuery()
                .eq(QuoteBomStatus::getOaFormItemId, context.item().getId())
                .last("LIMIT 1"));
    boolean inserting = status == null;
    if (status == null) {
      status = new QuoteBomStatus();
      status.setCreatedAt(LocalDateTime.now());
    }
    QuoteBomContext bomContext =
        quoteBomContextResolver.resolveWithExistingCostPeriod(
            context.form(), context.item(), periodMonth);
    status.setOaFormId(context.form().getId());
    status.setOaFormItemId(context.item().getId());
    status.setOaNo(context.form().getOaNo());
    status.setProductCode(productCode);
    status.setProductType(typeResult.productTypeCode());
    status.setBareProductCode(typeResult.productType() == QuoteProductType.BARE ? productCode : null);
    status.setNeedPackage(typeResult.productType() == QuoteProductType.BARE ? 1 : 0);
    status.setReferenceFinishedCode(trimToNull(referenceFinishedCode));
    status.setCostPeriodMonth(periodMonth);
    status.setProductModel(context.item().getSunlModel());
    status.setCustomerCode(bomContext.customerKey());
    status.setPackageType(context.item().getPackageType());
    status.setPackageMethod(bomContext.packageMethod());
    status.setTechnicianName(context.item().getTechnicianName());
    status.setReviewStatus(REVIEW_NOT_SUBMITTED);
    status.setCheckedAt(LocalDateTime.now());
    status.setUpdatedAt(LocalDateTime.now());
    status.setErrorMessage(trimToNull(errorMessage));
    if (typeResult.productType() == QuoteProductType.DATA_MISSING
        || typeResult.productType() == QuoteProductType.UNKNOWN) {
      status.setBomStatus(QuoteBomStatusCode.CHECK_FAILED.getCode());
    } else if (trimToNull(errorMessage) != null) {
      status.setBomStatus(QuoteBomStatusCode.ENTRY_PENDING.getCode());
    } else if (referenceFinishedCode != null || typeResult.productType() == QuoteProductType.BARE) {
      status.setBomStatus(QuoteBomStatusCode.REUSED_CURRENT_MONTH.getCode());
    } else {
      status.setBomStatus(QuoteBomStatusCode.SYNCED.getCode());
    }
    if (inserting) {
      quoteBomStatusMapper.insert(status);
    } else {
      quoteBomStatusMapper.updateById(status);
    }
    return status;
  }

  private QuoteBomContext resolvePreparationBomContext(QuoteContext context) {
    QuoteBomStatus latestStatus =
        quoteBomStatusMapper.selectOne(
            Wrappers.<QuoteBomStatus>lambdaQuery()
                .eq(QuoteBomStatus::getOaFormItemId, context.item().getId())
                .orderByDesc(QuoteBomStatus::getCheckedAt)
                .orderByDesc(QuoteBomStatus::getId)
                .last("LIMIT 1"));
    return quoteBomContextResolver.resolveWithExistingCostPeriod(
        context.form(),
        context.item(),
        latestStatus == null ? null : latestStatus.getCostPeriodMonth());
  }

  private QuoteBomPreparationRecord upsertPreparationRecord(
      QuoteContext context,
      QuoteBomStatus status,
      QuoteProductTypeResolveResult typeResult,
      String periodMonth,
      String preparationStatus,
      boolean needPackage,
      String referenceFinishedCode,
      String sourceTopProductCode,
      String reuseType,
      Object reuseSource,
      QuoteDataOrganization quoteDataOrganization,
      String errorMessage) {
    QuoteDataOrganization organization =
        MaterialOrganization.normalizeQuoteDataOrganization(quoteDataOrganization);
    QuoteBomPreparationRecord record =
        preparationRecordMapper.selectOne(
            Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
                .eq(QuoteBomPreparationRecord::getOaFormItemId, context.item().getId())
                .eq(QuoteBomPreparationRecord::getCostPeriodMonth, periodMonth)
                .last("LIMIT 1"));
    boolean inserting = record == null;
    if (record == null) {
      record = new QuoteBomPreparationRecord();
      record.setCreatedAt(LocalDateTime.now());
    }
    // 表上唯一键仍是 (oa_form_item_id, cost_period_month)。迁移前旧行的组织字段为空时，
    // 必须复用该行并补齐组织，不能按组织误判为新行后触发重复键。
    record.setActiveFlag(ACTIVE);
    record.setQuoteBomStatusId(status.getId());
    record.setOaFormId(context.form().getId());
    record.setOaFormItemId(context.item().getId());
    record.setOaNo(context.form().getOaNo());
    record.setQuoteProductCode(typeResult.quoteProductCode());
    record.setPriceOrgCode(organization.priceOrgCode());
    record.setMaterialOrganizationCode(organization.materialOrganizationCode());
    record.setProductType(typeResult.productTypeCode());
    record.setBareProductCode(
        typeResult.productType() == QuoteProductType.BARE
            ? typeResult.quoteProductCode()
            : null);
    record.setNeedPackage(needPackage ? 1 : 0);
    record.setReferenceFinishedCode(trimToNull(referenceFinishedCode));
    record.setSourceTopProductCode(trimToNull(sourceTopProductCode));
    record.setCostPeriodMonth(periodMonth);
    record.setPreparationStatus(preparationStatus);
    record.setReviewStatus(REVIEW_NOT_SUBMITTED);
    record.setTechnicianName(context.item().getTechnicianName());
    record.setReuseType(trimToNull(reuseType));
    record.setErrorMessage(trimToNull(errorMessage));
    applyReuseSource(record, reuseSource);
    record.setUpdatedAt(LocalDateTime.now());
    if (inserting) {
      preparationRecordMapper.insert(record);
    } else {
      preparationRecordMapper.updateById(record);
    }
    // 状态表必须始终指向本次按“产品行 + 核算月”实际复用/写入的准备记录。
    // 迁移前遗留记录被复用时也要刷新该引用，否则会出现状态期间已切月、
    // preparation_record_id 却仍指向旧月份记录的交叉引用。
    status.setPreparationRecordId(record.getId());
    quoteBomStatusMapper.updateById(status);
    return record;
  }

  private void applyReuseSource(QuoteBomPreparationRecord record, Object reuseSource) {
    record.setReusedFromOaNo(null);
    record.setReusedFromOaFormItemId(null);
    record.setReuseValidUntil(null);
    if (reuseSource instanceof SupplementBomReadResult supplement) {
      record.setReuseValidUntil(supplement.reuseValidUntil());
      return;
    }
    if (reuseSource instanceof QuoteBomPreparationRecord locked) {
      record.setReusedFromOaNo(locked.getOaNo());
      record.setReusedFromOaFormItemId(locked.getOaFormItemId());
      record.setReuseValidUntil(locked.getReuseValidUntil());
    }
  }

  private QuoteBomPreparationRecord findMonthlyLockedRecord(
      String productCode, String periodMonth, QuoteDataOrganization quoteDataOrganization) {
    QuoteDataOrganization organization =
        MaterialOrganization.normalizeQuoteDataOrganization(quoteDataOrganization);
    if (trimToNull(productCode) == null || trimToNull(periodMonth) == null) {
      return null;
    }
    return preparationRecordMapper.selectOne(
        Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
            .eq(QuoteBomPreparationRecord::getQuoteProductCode, productCode)
            .eq(QuoteBomPreparationRecord::getCostPeriodMonth, periodMonth)
            .eq(QuoteBomPreparationRecord::getPriceOrgCode, organization.priceOrgCode())
            .eq(
                QuoteBomPreparationRecord::getMaterialOrganizationCode,
                organization.materialOrganizationCode())
            .eq(QuoteBomPreparationRecord::getActiveFlag, ACTIVE)
            .in(
                QuoteBomPreparationRecord::getPreparationStatus,
                List.of(PREPARATION_READY, "CONFIRMED"))
            .orderByDesc(QuoteBomPreparationRecord::getUpdatedAt)
            .orderByDesc(QuoteBomPreparationRecord::getId)
            .last("LIMIT 1"));
  }

  private QuoteProductBomPreparationPreview toPreview(
      QuoteBomPreparationRecord record,
      QuoteBomStatus status,
      boolean ready,
      boolean needTechnicianTask,
      boolean abnormal,
      String bodyBomSource,
      boolean bodyBomReady,
      List<QuoteBomSourceLineDto> bodyLines,
      String referenceFinishedCode,
      boolean packageReferenceReady,
      List<PackageComponentStructureLineDto> packageLines,
      List<String> missingScopes,
      List<String> gapMessages) {
    return new QuoteProductBomPreparationPreview(
        record.getId(),
        status == null ? record.getQuoteBomStatusId() : status.getId(),
        record.getOaFormId(),
        record.getOaFormItemId(),
        record.getOaNo(),
        record.getQuoteProductCode(),
        record.getProductType(),
        record.getBareProductCode(),
        record.getNeedPackage() != null && record.getNeedPackage() == 1,
        record.getCostPeriodMonth(),
        record.getPreparationStatus(),
        record.getReviewStatus(),
        ready,
        needTechnicianTask,
        abnormal,
        bodyBomSource,
        bodyBomReady,
        bodyLines == null ? 0 : bodyLines.size(),
        firstText(referenceFinishedCode, record.getReferenceFinishedCode()),
        record.getSourceTopProductCode(),
        packageReferenceReady,
        packageLines == null ? 0 : packageLines.size(),
        record.getReusedFromOaNo(),
        record.getReusedFromOaFormItemId(),
        record.getReuseType(),
        record.getReuseValidUntil(),
        missingScopes == null ? List.of() : List.copyOf(missingScopes),
        gapMessages == null ? List.of() : List.copyOf(gapMessages),
        record.getErrorMessage(),
        bodyLines == null ? List.of() : List.copyOf(bodyLines),
        packageLines == null ? List.of() : List.copyOf(packageLines));
  }

  private LocalDate resolveQuoteDate(LocalDate quoteDate) {
    return quoteDate == null ? LocalDate.now() : quoteDate;
  }

  private List<String> compact(String... values) {
    List<String> result = new ArrayList<>();
    for (String value : values) {
      String text = trimToNull(value);
      if (text != null && !result.contains(text)) {
        result.add(text);
      }
    }
    return result;
  }

  private String firstText(String first, String second) {
    String value = trimToNull(first);
    return value == null ? trimToNull(second) : value;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record QuoteContext(OaForm form, OaFormItem item) {}
}
