package com.sanhua.marketingcost.service.collaboration.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import com.sanhua.marketingcost.service.collaboration.ApprovedResultFingerprints;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-05 已审核BOM/包装来源检查")
class ExistingApprovedSourceInspectorTest {

  private QuoteBomSupplementVersionMapper versionMapper;
  private QuoteBomSupplementDetailMapper detailMapper;
  private QuoteBomPackageReferenceMapper packageMapper;
  private PackageComponentStructureReadService packageService;
  private ExistingApprovedSourceInspector inspector;
  private ApprovedResultFingerprints fingerprints;

  @BeforeEach
  void setUp() {
    versionMapper = mock(QuoteBomSupplementVersionMapper.class);
    detailMapper = mock(QuoteBomSupplementDetailMapper.class);
    packageMapper = mock(QuoteBomPackageReferenceMapper.class);
    packageService = mock(PackageComponentStructureReadService.class);
    fingerprints = new ApprovedResultFingerprints();
    inspector = new ExistingApprovedSourceInspector(
        new ApprovedResultSourceSnapshotReader(
            versionMapper, detailMapper, packageMapper, packageService, fingerprints),
        fingerprints);
  }

  @Test
  @DisplayName("FULL_BOM必须指向同产品的已审核活动版本且存在明细")
  void fullBomReady() {
    QuoteCollaborationApprovedResult result = fullBomResult();
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setId(9L);
    version.setQuoteProductCode("P-1");
    version.setVersionStatus("APPROVED");
    version.setActiveFlag(1);
    when(versionMapper.selectById(9L)).thenReturn(version);
    QuoteBomSupplementDetail line = new QuoteBomSupplementDetail();
    line.setLineNo(1);
    line.setParentCode("P-1");
    line.setMaterialCode("RAW-1");
    when(detailMapper.selectList(any())).thenReturn(List.of(line));
    result.setStructureFingerprint(fingerprints.fullBom(List.of(line)));

    ApprovedSourceInspection inspection = inspector.inspect(
        result, context(), CurrentU9BomResult.notFound("U9无BOM"));

    assertThat(inspection.status()).isEqualTo(ApprovedSourceInspection.Status.READY);
    assertThat(inspection.source()).isEqualTo("ELECTRONIC_DRAWING");
    assertThat(inspection.lineCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("FULL_BOM来源产品不一致时拒绝复用")
  void fullBomWrongProductIsInvalid() {
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setId(9L);
    version.setQuoteProductCode("OTHER");
    version.setVersionStatus("APPROVED");
    version.setActiveFlag(1);
    when(versionMapper.selectById(9L)).thenReturn(version);

    ApprovedSourceInspection inspection = inspector.inspect(
        fullBomResult(), context(), CurrentU9BomResult.notFound("U9无BOM"));

    assertThat(inspection.status()).isEqualTo(ApprovedSourceInspection.Status.INVALID);
  }

  @Test
  @DisplayName("BARE_PACKAGE按审核结果的精确包装记录读取完整结构")
  void barePackageReady() {
    QuoteCollaborationApprovedResult result = new QuoteCollaborationApprovedResult();
    result.setResultType("BARE_PACKAGE");
    result.setSourceObjectType("PACKAGE_REFERENCE");
    result.setSourceSystem("QUOTE_PACKAGE");
    result.setSourceObjectId(11L);
    result.setStructureFingerprint("pkg-fp");
    QuoteBomPackageReference reference = new QuoteBomPackageReference();
    reference.setId(11L);
    reference.setBareProductCode("P-1");
    reference.setReferenceStatus("APPROVED");
    reference.setActiveFlag(1);
    reference.setReferenceFinishedCode("FIN-1");
    reference.setSourceTopProductCode("FIN-1");
    reference.setPeriodMonth("2026-08");
    when(packageMapper.selectById(11L)).thenReturn(reference);
    PackageComponentStructureLineDto line = packageLine();
    result.setStructureFingerprint(fingerprints.packageStructure(List.of(line)));
    CurrentU9BomResult u9 = CurrentU9BomResult.available(
        "U9", "BODY-V1", null, 5, "c".repeat(64));
    result.setU9ContextFingerprint(fingerprints.u9Context(context(), u9));
    when(packageService.readByReference("FIN-1", "FIN-1", "2026-08", "210", "COMMERCIAL"))
        .thenReturn(new PackageComponentStructureReadResult(
            "FIN-1", "FIN-1", "2026-08", 11L, true, List.of(line), List.of()));

    ApprovedSourceInspection inspection = inspector.inspect(result, context(), u9);

    assertThat(inspection.status()).isEqualTo(ApprovedSourceInspection.Status.READY);
    assertThat(inspection.source()).isEqualTo("QUOTE_PACKAGE");
  }

  @Test
  @DisplayName("FULL_BOM明细结构变化时拒绝复用")
  void fullBomFingerprintChangeIsInvalid() {
    QuoteCollaborationApprovedResult result = fullBomResult();
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setId(9L);
    version.setQuoteProductCode("P-1");
    version.setVersionStatus("APPROVED");
    version.setActiveFlag(1);
    QuoteBomSupplementDetail line = new QuoteBomSupplementDetail();
    line.setLineNo(1);
    line.setParentCode("P-1");
    line.setMaterialCode("CHANGED");
    when(versionMapper.selectById(9L)).thenReturn(version);
    when(detailMapper.selectList(any())).thenReturn(List.of(line));

    ApprovedSourceInspection inspection = inspector.inspect(
        result, context(), CurrentU9BomResult.notFound("U9无BOM"));

    assertThat(inspection.status()).isEqualTo(ApprovedSourceInspection.Status.INVALID);
    assertThat(inspection.message()).contains("结构指纹已变化");
  }

  @Test
  @DisplayName("裸品U9本体上下文变化时拒绝复用包装方案")
  void barePackageU9ContextChangeIsInvalid() {
    QuoteCollaborationApprovedResult result = new QuoteCollaborationApprovedResult();
    result.setResultType("BARE_PACKAGE");
    result.setSourceObjectType("PACKAGE_REFERENCE");
    result.setSourceSystem("QUOTE_PACKAGE");
    result.setSourceObjectId(11L);
    result.setStructureFingerprint("pkg-fp");
    result.setU9ContextFingerprint("old-context");
    QuoteBomPackageReference reference = new QuoteBomPackageReference();
    reference.setId(11L);
    reference.setBareProductCode("P-1");
    reference.setReferenceStatus("APPROVED");
    reference.setActiveFlag(1);
    reference.setReferenceFinishedCode("FIN-1");
    reference.setSourceTopProductCode("FIN-1");
    reference.setPeriodMonth("2026-08");
    PackageComponentStructureLineDto line = packageLine();
    result.setStructureFingerprint(fingerprints.packageStructure(List.of(line)));
    when(packageMapper.selectById(11L)).thenReturn(reference);
    when(packageService.readByReference("FIN-1", "FIN-1", "2026-08", "210", "COMMERCIAL"))
        .thenReturn(new PackageComponentStructureReadResult(
            "FIN-1", "FIN-1", "2026-08", 11L, true, List.of(line), List.of()));

    ApprovedSourceInspection inspection = inspector.inspect(
        result, context(), CurrentU9BomResult.available(
            "U9", "BODY-V2", null, 6, "d".repeat(64)));

    assertThat(inspection.status()).isEqualTo(ApprovedSourceInspection.Status.INVALID);
    assertThat(inspection.message()).contains("U9本体上下文已变化");
  }

  private QuoteCollaborationApprovedResult fullBomResult() {
    QuoteCollaborationApprovedResult result = new QuoteCollaborationApprovedResult();
    result.setResultType("FULL_BOM");
    result.setSourceObjectType("SUPPLEMENT_VERSION");
    result.setSourceSystem("ELECTRONIC_DRAWING");
    result.setSourceObjectId(9L);
    result.setStructureFingerprint("bom-fp");
    return result;
  }

  private QuoteCollaborationScanContext context() {
    return new QuoteCollaborationScanContext(
        1L,
        2L,
        "OA-1",
        "2026-08",
        "COMMERCIAL",
        "P-1",
        "产品",
        "规格",
        "型号",
        "210",
        "COMMERCIAL",
        LocalDate.of(2026, 8, 13),
        LocalDateTime.of(2026, 8, 13, 10, 0));
  }

  private PackageComponentStructureLineDto packageLine() {
    return new PackageComponentStructureLineDto(
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null);
  }
}
