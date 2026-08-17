package com.sanhua.marketingcost.service.collaboration.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.collaboration.ApprovedResultFingerprints;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

@DisplayName("QCBP-05 U9当前BOM结构化适配")
class FormalU9CollaborationBomGatewayTest {

  private FormalBomReadService formalBomReadService;
  private BomRawHierarchyMapper rawMapper;
  private FormalU9CollaborationBomGateway gateway;

  @BeforeEach
  void setUp() {
    formalBomReadService = mock(FormalBomReadService.class);
    rawMapper = mock(BomRawHierarchyMapper.class);
    gateway = new FormalU9CollaborationBomGateway(
        formalBomReadService, rawMapper, new ApprovedResultFingerprints());
  }

  @Test
  @DisplayName("U9成功且有明细返回AVAILABLE和真实行数")
  void available() {
    QuoteBomSourceLineDto first = line("V6");
    QuoteBomSourceLineDto second = line(null);
    when(formalBomReadService.read(
            eq("P-1"), eq("2026-08"), isNull(), any(LocalDate.class),
            any(QuoteDataOrganization.class)))
        .thenReturn(new FormalBomReadResult("P-1", "2026-08", null, true,
            List.of(first, second), null));

    CurrentU9BomResult result = gateway.read(context());

    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.AVAILABLE);
    assertThat(result.source()).isEqualTo("U9");
    assertThat(result.bomVersion()).isEqualTo("V6");
    assertThat(result.lineCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("U9明确无BOM且其他组织也无记录返回NOT_FOUND")
  void notFound() {
    when(formalBomReadService.read(
            any(), any(), any(), any(LocalDate.class), any(QuoteDataOrganization.class)))
        .thenReturn(new FormalBomReadResult("P-1", "2026-08", null, false,
            List.of(), "未找到正式BOM"));
    when(rawMapper.selectCount(any())).thenReturn(0L);

    CurrentU9BomResult result = gateway.read(context());

    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.NOT_FOUND);
    assertThat(result.message()).contains("未找到正式BOM");
  }

  @Test
  @DisplayName("目标组织无BOM但其他组织存在时返回ORGANIZATION_MISMATCH")
  void organizationMismatch() {
    when(formalBomReadService.read(
            any(), any(), any(), any(LocalDate.class), any(QuoteDataOrganization.class)))
        .thenReturn(new FormalBomReadResult("P-1", "2026-08", null, false,
            List.of(), "未找到正式BOM"));
    when(rawMapper.selectCount(any())).thenReturn(1L);

    CurrentU9BomResult result = gateway.read(context());

    assertThat(result.status())
        .isEqualTo(CurrentU9BomResult.Status.ORGANIZATION_MISMATCH);
    assertThat(result.message()).contains("禁止跨组织兜底");
  }

  @Test
  @DisplayName("U9超时与普通错误严格区分")
  void timeout() {
    when(formalBomReadService.read(
            any(), any(), any(), any(LocalDate.class), any(QuoteDataOrganization.class)))
        .thenThrow(new QueryTimeoutException("query timeout"));

    CurrentU9BomResult result = gateway.read(context());

    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.TIMEOUT);
  }

  @Test
  @DisplayName("U9返回空对象或空明细均是DATA_EMPTY，不冒充无BOM")
  void dataEmpty() {
    when(formalBomReadService.read(
            any(), any(), any(), any(LocalDate.class), any(QuoteDataOrganization.class)))
        .thenReturn(null);
    assertThat(gateway.read(context()).status()).isEqualTo(CurrentU9BomResult.Status.DATA_EMPTY);

    when(formalBomReadService.read(
            any(), any(), any(), any(LocalDate.class), any(QuoteDataOrganization.class)))
        .thenReturn(new FormalBomReadResult("P-1", "2026-08", null, true, List.of(), null));
    assertThat(gateway.read(context()).status()).isEqualTo(CurrentU9BomResult.Status.DATA_EMPTY);
  }

  @Test
  @DisplayName("U9存在但结构不连通或展开失败不能冒充无BOM发给技术补录")
  void invalidU9StructureIsSystemDataError() {
    when(formalBomReadService.read(
            any(), any(), any(), any(LocalDate.class), any(QuoteDataOrganization.class)))
        .thenReturn(new FormalBomReadResult(
            "P-1", "2026-08", null, false, List.of(), "未找到有效连通 BOM"));
    assertThat(gateway.read(context()).status()).isEqualTo(CurrentU9BomResult.Status.DATA_EMPTY);

    when(formalBomReadService.read(
            any(), any(), any(), any(LocalDate.class), any(QuoteDataOrganization.class)))
        .thenReturn(new FormalBomReadResult(
            "P-1", "2026-08", null, false, List.of(), "跨组织制造 BOM 展开失败：缺少下级"));
    assertThat(gateway.read(context()).status()).isEqualTo(CurrentU9BomResult.Status.ERROR);
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

  private QuoteBomSourceLineDto line(String bomVersion) {
    return new QuoteBomSourceLineDto(
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, bomVersion, null, null,
        null, null, null, null, null, null, null, null, null, null);
  }
}
