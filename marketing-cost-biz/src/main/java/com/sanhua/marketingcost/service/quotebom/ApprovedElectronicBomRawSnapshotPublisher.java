package com.sanhua.marketingcost.service.quotebom;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 将财务已审核的电子图库 BOM 固化到统一原始层，供最终有效 BOM 按批次读取。 */
@Service
public class ApprovedElectronicBomRawSnapshotPublisher {
  public static final String SOURCE_TYPE = "E_DRAWING";
  public static final String BATCH_PREFIX = "SUPPLEMENT_VERSION:";
  private static final String DEFAULT_PURPOSE = "主制造";

  private final QuoteBomSupplementDetailMapper detailMapper;
  private final BomRawHierarchyMapper rawMapper;

  public ApprovedElectronicBomRawSnapshotPublisher(
      QuoteBomSupplementDetailMapper detailMapper, BomRawHierarchyMapper rawMapper) {
    this.detailMapper = detailMapper;
    this.rawMapper = rawMapper;
  }

  public String publish(PublicationContext product) {
    if (product == null || product.supplementVersionId() == null
        || product.supplementVersionId() <= 0) {
      throw new IllegalArgumentException("电子图库BOM缺少补录版本");
    }
    String productCode =
        required(
            first(product.productCode(), product.temporaryProductKey()),
            "电子图库BOM缺少产品料号、型号或图号身份");
    String priceOrg = required(product.priceOrgCode(), "电子图库BOM缺少报价组织");
    String businessUnit = required(product.businessUnitType(), "电子图库BOM缺少业务单元");
    String batchId = BATCH_PREFIX + product.supplementVersionId();
    List<QuoteBomSupplementDetail> details = detailMapper.selectList(
        Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
            .eq(QuoteBomSupplementDetail::getSupplementVersionId, product.supplementVersionId())
            .orderByAsc(QuoteBomSupplementDetail::getLevel)
            .orderByAsc(QuoteBomSupplementDetail::getPath)
            .orderByAsc(QuoteBomSupplementDetail::getSortSeq)
            .orderByAsc(QuoteBomSupplementDetail::getLineNo));
    if (details == null || details.isEmpty()) {
      throw new IllegalStateException("已审核电子图库BOM没有明细");
    }
    List<BomRawHierarchy> existing = rawMapper.selectList(
        Wrappers.<BomRawHierarchy>lambdaQuery()
            .eq(BomRawHierarchy::getBuildBatchId, batchId)
            .eq(BomRawHierarchy::getPriceOrgCode, priceOrg)
            .eq(BomRawHierarchy::getTopProductCode, productCode));
    if (existing != null && !existing.isEmpty()) {
      if (existing.size() != details.size()) {
        throw new IllegalStateException("电子图库BOM原始快照不完整，禁止覆盖");
      }
      validateExisting(existing, details, product, productCode, priceOrg, businessUnit, batchId);
      return batchId;
    }

    Set<String> parentPaths = structuralParentPaths(details);
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    YearMonth approvedMonth = YearMonth.parse(required(
        product.accountingMonth(), "电子图库BOM缺少核算月份"));
    for (QuoteBomSupplementDetail detail : details.stream()
        .sorted(Comparator.comparing(
            row -> row.getLineNo() == null ? Integer.MAX_VALUE : row.getLineNo()))
        .toList()) {
      BomRawHierarchy row = toRaw(
          detail, product, productCode, priceOrg, businessUnit, batchId,
          parentPaths, approvedMonth, now);
      rawMapper.insert(row);
    }
    return batchId;
  }

  private BomRawHierarchy toRaw(
      QuoteBomSupplementDetail detail,
      PublicationContext product,
      String productCode,
      String priceOrg,
      String businessUnit,
      String batchId,
      Set<String> parentPaths,
      YearMonth approvedMonth,
      LocalDateTime builtAt) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setPriceOrgCode(priceOrg);
    row.setTopProductCode(productCode);
    row.setMaterialCode(required(detail.getMaterialCode(), "电子图库BOM节点料号不能为空"));
    row.setParentCode(detail.getLevel() != null && detail.getLevel() == 0
        ? row.getMaterialCode()
        : required(detail.getParentCode(), "电子图库BOM父项料号不能为空"));
    row.setLevel(detail.getLevel());
    row.setPath(required(detail.getPath(), "电子图库BOM节点路径不能为空"));
    row.setSortSeq(detail.getSortSeq());
    row.setSourceU9RowId(detail.getSourceU9BomId() == null
        ? detail.getSourceRawHierarchyId()
        : detail.getSourceU9BomId());
    row.setSourceLineKey(sourceLineKey(product.supplementVersionId(), detail));
    row.setQtyPerParent(detail.getQtyPerParent());
    row.setQtyPerTop(detail.getQtyPerTop());
    row.setMaterialName(detail.getMaterialName());
    row.setMaterialSpec(detail.getMaterialSpec());
    row.setShapeAttr(detail.getShapeAttr());
    row.setSourceCategory(detail.getSourceCategory());
    row.setCostElementCode(detail.getCostElementCode());
    row.setMaterialCategory1(detail.getMainCategoryCode());
    row.setBomPurpose(DEFAULT_PURPOSE);
    row.setBomVersion(first(detail.getBomVersion(), "ED-" + product.supplementVersionId()));
    row.setBomStatus("APPROVED");
    // 叶子身份属于结构位置，不能按料号推断：同一料号可能在一个分支是父件，
    // 在另一个分支又是末级采购件。
    row.setIsLeaf(parentPaths.contains(normalizePath(row.getPath())) ? 0 : 1);
    row.setEffectiveFrom(approvedMonth.atDay(1));
    row.setEffectiveTo(null);
    // source_type 表示整批正式来源。混合树里的 U9 展开节点仍属于电子图库补充批次；
    // 其精确 U9 血缘由 source_u9_row_id 保存，不能标成正式 U9，否则后续 U9 首查
    // 会把这些无 U9 根节点的复制行误判成“存在但断根的正式 U9 BOM”。
    row.setSourceType(SOURCE_TYPE);
    row.setSourceImportBatchId(batchId);
    row.setBuildBatchId(batchId);
    row.setBuiltAt(builtAt);
    row.setBusinessUnitType(businessUnit);
    return row;
  }

  /** 当前报价准备价格时复用同一字段转换；只返回草稿，不发布原始层或审批版本。 */
  public List<BomRawHierarchy> preview(PublicationContext product, String batchId) {
    List<QuoteBomSupplementDetail> details = detailMapper.selectList(
        Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
            .eq(QuoteBomSupplementDetail::getSupplementVersionId, product.supplementVersionId())
            .orderByAsc(QuoteBomSupplementDetail::getLineNo));
    if (details == null || details.isEmpty()) throw new IllegalStateException("电子图库草稿没有已组树明细");
    Set<String> parents = structuralParentPaths(details);
    return details.stream().map(detail -> {
      var row = toRaw(detail, product, product.productCode(), product.priceOrgCode(),
          product.businessUnitType(), batchId, parents, YearMonth.parse(product.accountingMonth()), null);
      row.setBomStatus("DRAFT");
      return row;
    }).toList();
  }

  private void validateExisting(
      List<BomRawHierarchy> existing,
      List<QuoteBomSupplementDetail> details,
      PublicationContext product,
      String productCode,
      String priceOrg,
      String businessUnit,
      String batchId) {
    Set<String> parentPaths = structuralParentPaths(details);
    YearMonth month = YearMonth.parse(required(
        product.accountingMonth(), "电子图库BOM缺少核算月份"));
    Map<String, BomRawHierarchy> byKey = existing.stream().collect(Collectors.toMap(
        BomRawHierarchy::getSourceLineKey, Function.identity(), (first, ignored) -> first));
    for (QuoteBomSupplementDetail detail : details) {
      BomRawHierarchy expected = toRaw(
          detail, product, productCode, priceOrg, businessUnit, batchId,
          parentPaths, month, null);
      BomRawHierarchy actual = byKey.get(expected.getSourceLineKey());
      if (actual == null || !sameRaw(actual, expected)) {
        throw new IllegalStateException("电子图库BOM原始快照与已发布版本不一致，禁止覆盖");
      }
    }
  }

  private boolean sameRaw(BomRawHierarchy left, BomRawHierarchy right) {
    return Objects.equals(left.getPriceOrgCode(), right.getPriceOrgCode())
        && Objects.equals(left.getTopProductCode(), right.getTopProductCode())
        && Objects.equals(left.getParentCode(), right.getParentCode())
        && Objects.equals(left.getMaterialCode(), right.getMaterialCode())
        && Objects.equals(left.getLevel(), right.getLevel())
        && Objects.equals(left.getPath(), right.getPath())
        && Objects.equals(left.getSortSeq(), right.getSortSeq())
        && Objects.equals(left.getSourceU9RowId(), right.getSourceU9RowId())
        && decimalEquals(left.getQtyPerParent(), right.getQtyPerParent())
        && decimalEquals(left.getQtyPerTop(), right.getQtyPerTop())
        && Objects.equals(left.getMaterialName(), right.getMaterialName())
        && Objects.equals(left.getMaterialSpec(), right.getMaterialSpec())
        && Objects.equals(left.getShapeAttr(), right.getShapeAttr())
        && Objects.equals(left.getSourceCategory(), right.getSourceCategory())
        && Objects.equals(left.getCostElementCode(), right.getCostElementCode())
        && Objects.equals(left.getMaterialCategory1(), right.getMaterialCategory1())
        && Objects.equals(left.getBomPurpose(), right.getBomPurpose())
        && Objects.equals(left.getBomVersion(), right.getBomVersion())
        && Objects.equals(left.getBomStatus(), right.getBomStatus())
        && Objects.equals(left.getIsLeaf(), right.getIsLeaf())
        && Objects.equals(left.getEffectiveFrom(), right.getEffectiveFrom())
        && Objects.equals(left.getEffectiveTo(), right.getEffectiveTo())
        && Objects.equals(left.getSourceType(), right.getSourceType())
        && Objects.equals(left.getSourceImportBatchId(), right.getSourceImportBatchId())
        && Objects.equals(left.getBuildBatchId(), right.getBuildBatchId())
        && Objects.equals(left.getBusinessUnitType(), right.getBusinessUnitType());
  }

  private boolean decimalEquals(BigDecimal left, BigDecimal right) {
    if (left == null || right == null) return left == right;
    return left.compareTo(right) == 0;
  }

  private Set<String> structuralParentPaths(List<QuoteBomSupplementDetail> details) {
    Set<String> result = new HashSet<>();
    for (QuoteBomSupplementDetail detail : details) {
      String parentPath = parentPath(detail == null ? null : detail.getPath());
      if (parentPath != null) {
        result.add(parentPath);
      }
    }
    return result;
  }

  private String parentPath(String path) {
    String normalized = normalizePath(path);
    if (normalized == null) {
      return null;
    }
    int separator = normalized.lastIndexOf('/', normalized.length() - 2);
    return separator <= 0 ? null : normalized.substring(0, separator + 1);
  }

  private String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return null;
    }
    String normalized = path.trim();
    return normalized.endsWith("/") ? normalized : normalized + "/";
  }

  private String sourceLineKey(Long versionId, QuoteBomSupplementDetail detail) {
    return first(detail.getNodeSourceType(), SOURCE_TYPE) + "|" + versionId + "|"
        + detail.getLineNo() + "|"
        + first(detail.getSourceElectronicNodeId() == null
            ? null : detail.getSourceElectronicNodeId().toString(), "NO_ED") + "|"
        + first(detail.getSourceRawHierarchyId() == null
            ? null : detail.getSourceRawHierarchyId().toString(), "NO_U9");
  }

  private String required(String value, String message) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
    return value.trim();
  }

  private String first(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  /** 正式BOM发布所需的最小身份，不携带任何协作任务实体。 */
  public record PublicationContext(
      Long supplementVersionId,
      String productCode,
      String temporaryProductKey,
      String priceOrgCode,
      String businessUnitType,
      String accountingMonth) {}
}
