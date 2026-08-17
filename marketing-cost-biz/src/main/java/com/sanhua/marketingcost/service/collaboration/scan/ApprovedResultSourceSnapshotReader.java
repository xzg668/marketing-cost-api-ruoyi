package com.sanhua.marketingcost.service.collaboration.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import com.sanhua.marketingcost.service.collaboration.ApprovedResultFingerprints;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ResultSourceObjectType;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ResultSourceSystem;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 只从正式、活动来源读取结构，并以实际明细重新计算指纹。 */
@Component
public class ApprovedResultSourceSnapshotReader {

  private static final String APPROVED = "APPROVED";
  private static final int ACTIVE = 1;

  private final QuoteBomSupplementVersionMapper supplementVersionMapper;
  private final QuoteBomSupplementDetailMapper supplementDetailMapper;
  private final QuoteBomPackageReferenceMapper packageReferenceMapper;
  private final PackageComponentStructureReadService packageStructureService;
  private final ApprovedResultFingerprints fingerprints;

  public ApprovedResultSourceSnapshotReader(
      QuoteBomSupplementVersionMapper supplementVersionMapper,
      QuoteBomSupplementDetailMapper supplementDetailMapper,
      QuoteBomPackageReferenceMapper packageReferenceMapper,
      PackageComponentStructureReadService packageStructureService,
      ApprovedResultFingerprints fingerprints) {
    this.supplementVersionMapper = supplementVersionMapper;
    this.supplementDetailMapper = supplementDetailMapper;
    this.packageReferenceMapper = packageReferenceMapper;
    this.packageStructureService = packageStructureService;
    this.fingerprints = fingerprints;
  }

  public ApprovedResultSourceSnapshot readFullBom(Long sourceObjectId, String productCode) {
    if (sourceObjectId == null || !StringUtils.hasText(productCode)) {
      return ApprovedResultSourceSnapshot.invalid("完整BOM来源对象或产品料号为空");
    }
    try {
      QuoteBomSupplementVersion version = supplementVersionMapper.selectById(sourceObjectId);
      if (version == null) {
        return ApprovedResultSourceSnapshot.notFound("审核结果指向的BOM补录版本不存在");
      }
      if (!APPROVED.equals(version.getVersionStatus())
          || !Integer.valueOf(ACTIVE).equals(version.getActiveFlag())
          || !sameText(productCode, version.getQuoteProductCode())) {
        return ApprovedResultSourceSnapshot.invalid(
            "审核结果指向的BOM补录版本已失效或产品不一致");
      }
      List<QuoteBomSupplementDetail> details = supplementDetailMapper.selectList(
          Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
              .eq(QuoteBomSupplementDetail::getSupplementVersionId, version.getId())
              .orderByAsc(QuoteBomSupplementDetail::getLineNo));
      if (details == null || details.isEmpty()) {
        return ApprovedResultSourceSnapshot.notFound("审核结果指向的电子图库BOM明细为空");
      }
      return ApprovedResultSourceSnapshot.ready(
          ResultSourceObjectType.SUPPLEMENT_VERSION.code(),
          ResultSourceSystem.ELECTRONIC_DRAWING.code(),
          version.getVersionNo() == null ? version.getBomSource() : "V" + version.getVersionNo(),
          details.size(), fingerprints.fullBom(details));
    } catch (RuntimeException exception) {
      return ApprovedResultSourceSnapshot.error(
          "审核结果来源读取失败：" + exceptionMessage(exception));
    }
  }

  public ApprovedResultSourceSnapshot readBarePackage(
      Long sourceObjectId,
      String productCode,
      String priceOrgCode,
      String materialOrganizationCode) {
    if (sourceObjectId == null || !StringUtils.hasText(productCode)) {
      return ApprovedResultSourceSnapshot.invalid("包装来源对象或产品料号为空");
    }
    try {
      QuoteBomPackageReference reference = packageReferenceMapper.selectById(sourceObjectId);
      if (reference == null) {
        return ApprovedResultSourceSnapshot.notFound("审核结果指向的包装方案不存在");
      }
      if (!APPROVED.equals(reference.getReferenceStatus())
          || !Integer.valueOf(ACTIVE).equals(reference.getActiveFlag())
          || !sameText(productCode, reference.getBareProductCode())) {
        return ApprovedResultSourceSnapshot.invalid(
            "审核结果指向的包装方案已失效或裸品不一致");
      }
      PackageComponentStructureReadResult structure = packageStructureService.readByReference(
          reference.getReferenceFinishedCode(), reference.getSourceTopProductCode(),
          reference.getPeriodMonth(), priceOrgCode, materialOrganizationCode);
      if (structure == null) {
        return ApprovedResultSourceSnapshot.error("包装结构读取没有返回结果");
      }
      if (!structure.found()) {
        String message = structure.gaps() == null || structure.gaps().isEmpty()
            ? "包装结构不存在或不完整" : String.join("；", structure.gaps());
        return ApprovedResultSourceSnapshot.invalid(message);
      }
      if (structure.lines() == null || structure.lines().isEmpty()) {
        return ApprovedResultSourceSnapshot.notFound("包装方案没有结构明细");
      }
      return ApprovedResultSourceSnapshot.ready(
          ResultSourceObjectType.PACKAGE_REFERENCE.code(),
          ResultSourceSystem.QUOTE_PACKAGE.code(),
          "PACKAGE_REFERENCE:" + reference.getId(),
          structure.lines().size(), fingerprints.packageStructure(structure.lines()));
    } catch (RuntimeException exception) {
      return ApprovedResultSourceSnapshot.error(
          "审核结果来源读取失败：" + exceptionMessage(exception));
    }
  }

  private boolean sameText(String left, String right) {
    return StringUtils.hasText(left) && StringUtils.hasText(right)
        && left.trim().equals(right.trim());
  }

  private String exceptionMessage(RuntimeException exception) {
    return StringUtils.hasText(exception.getMessage())
        ? exception.getMessage().trim() : exception.getClass().getSimpleName();
  }
}
