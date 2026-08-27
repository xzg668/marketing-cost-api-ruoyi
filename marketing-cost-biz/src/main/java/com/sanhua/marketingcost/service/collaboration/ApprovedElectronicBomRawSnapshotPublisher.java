package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

  public String publish(QuoteCollaborationProductTask product) {
    if (product == null || product.getSupplementVersionId() == null
        || product.getSupplementVersionId() <= 0) {
      throw new IllegalArgumentException("电子图库BOM缺少补录版本");
    }
    String productCode =
        required(
            first(product.getProductCode(), product.getTemporaryProductKey()),
            "电子图库BOM缺少产品料号、型号或图号身份");
    String priceOrg = required(product.getPriceOrgCode(), "电子图库BOM缺少报价组织");
    String businessUnit = required(product.getBusinessUnitType(), "电子图库BOM缺少业务单元");
    String batchId = BATCH_PREFIX + product.getSupplementVersionId();
    List<QuoteBomSupplementDetail> details = detailMapper.selectList(
        Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
            .eq(QuoteBomSupplementDetail::getSupplementVersionId, product.getSupplementVersionId())
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
      return batchId;
    }

    Set<String> parents = new HashSet<>();
    for (QuoteBomSupplementDetail detail : details) {
      if (detail.getLevel() != null && detail.getLevel() > 0
          && StringUtils.hasText(detail.getParentCode())) {
        parents.add(detail.getParentCode().trim());
      }
    }
    LocalDateTime now = LocalDateTime.now();
    YearMonth approvedMonth = YearMonth.parse(required(
        product.getAccountingMonth(), "电子图库BOM缺少核算月份"));
    for (QuoteBomSupplementDetail detail : details.stream()
        .sorted(Comparator.comparing(
            row -> row.getLineNo() == null ? Integer.MAX_VALUE : row.getLineNo()))
        .toList()) {
      BomRawHierarchy row = new BomRawHierarchy();
      row.setPriceOrgCode(priceOrg);
      row.setTopProductCode(productCode);
      row.setParentCode(required(detail.getParentCode(), "电子图库BOM父项料号不能为空"));
      row.setMaterialCode(required(detail.getMaterialCode(), "电子图库BOM节点料号不能为空"));
      row.setLevel(detail.getLevel());
      row.setPath(required(detail.getPath(), "电子图库BOM节点路径不能为空"));
      row.setSortSeq(detail.getSortSeq());
      row.setSourceLineKey(
          SOURCE_TYPE + "|" + product.getSupplementVersionId() + "|" + detail.getLineNo());
      row.setQtyPerParent(detail.getQtyPerParent());
      row.setQtyPerTop(detail.getQtyPerTop());
      row.setMaterialName(detail.getMaterialName());
      row.setMaterialSpec(detail.getMaterialSpec());
      row.setShapeAttr(detail.getShapeAttr());
      row.setSourceCategory(detail.getSourceCategory());
      row.setCostElementCode(detail.getCostElementCode());
      row.setMaterialCategory1(detail.getMainCategoryCode());
      row.setBomPurpose(DEFAULT_PURPOSE);
      row.setBomVersion(first(detail.getBomVersion(), "ED-" + product.getSupplementVersionId()));
      row.setBomStatus("APPROVED");
      row.setIsLeaf(parents.contains(row.getMaterialCode()) ? 0 : 1);
      row.setEffectiveFrom(approvedMonth.atDay(1));
      row.setEffectiveTo(null);
      row.setSourceType(SOURCE_TYPE);
      row.setSourceImportBatchId(batchId);
      row.setBuildBatchId(batchId);
      row.setBuiltAt(now);
      row.setBusinessUnitType(businessUnit);
      rawMapper.insert(row);
    }
    return batchId;
  }

  private String required(String value, String message) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
    return value.trim();
  }

  private String first(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }
}
