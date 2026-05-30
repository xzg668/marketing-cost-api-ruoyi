package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.quotebom.SupplementBomReadResult;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("补录 BOM 只读服务")
class SupplementBomReadServiceImplTest {

  @Test
  @DisplayName("只读取 6 个月有效期内的已审核补录 BOM")
  void readsApprovedSupplementWithinSixMonthReuseWindow() {
    QuoteBomSupplementVersionMapper versionMapper = mock(QuoteBomSupplementVersionMapper.class);
    QuoteBomSupplementDetailMapper detailMapper = mock(QuoteBomSupplementDetailMapper.class);
    QuoteBomSupplementVersion expired = version(1L, "2025-11", "2026-04-30");
    QuoteBomSupplementVersion valid = version(2L, "2026-01", "2026-06-30");
    when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(expired, valid));
    when(detailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(detail(20L, 2L)));

    SupplementBomReadServiceImpl service =
        new SupplementBomReadServiceImpl(versionMapper, detailMapper);
    SupplementBomReadResult result =
        service.readApproved("MAT-001", "NON_BARE", "NON_BARE_FULL_BOM", "2026-05");

    assertThat(result.found()).isTrue();
    assertThat(result.supplementVersionId()).isEqualTo(2L);
    assertThat(result.reuseValidUntil()).isEqualTo(LocalDate.parse("2026-06-30"));
    assertThat(result.lines()).singleElement().satisfies(line -> {
      assertThat(line.materialCode()).isEqualTo("CHILD-001");
      assertThat(line.parentBaseQty()).isEqualByComparingTo("1.00000000");
      assertThat(line.manualFlag()).isEqualTo(1);
    });
  }

  @Test
  @DisplayName("已审核补录 BOM 超过 6 个月复用有效期时返回缺口")
  void returnsGapWhenApprovedSupplementExpired() {
    QuoteBomSupplementVersionMapper versionMapper = mock(QuoteBomSupplementVersionMapper.class);
    QuoteBomSupplementDetailMapper detailMapper = mock(QuoteBomSupplementDetailMapper.class);
    when(versionMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(version(1L, "2025-10", "2026-04-30")));

    SupplementBomReadServiceImpl service =
        new SupplementBomReadServiceImpl(versionMapper, detailMapper);
    SupplementBomReadResult result =
        service.readApproved("MAT-001", "NON_BARE", "NON_BARE_FULL_BOM", "2026-05");

    assertThat(result.found()).isFalse();
    assertThat(result.lines()).isEmpty();
    assertThat(result.gapMessage()).contains("6 个月");
  }

  private QuoteBomSupplementVersion version(Long id, String periodMonth, String reuseValidUntil) {
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setId(id);
    version.setTaskId(1000L + id);
    version.setTaskNo("TASK-" + id);
    version.setQuoteProductCode("MAT-001");
    version.setProductType("NON_BARE");
    version.setSupplementScope("NON_BARE_FULL_BOM");
    version.setBomSource("TECH_SUPPLEMENT");
    version.setVersionStatus("APPROVED");
    version.setActiveFlag(1);
    version.setPeriodMonth(periodMonth);
    version.setEffectiveFrom(LocalDate.parse(periodMonth + "-01"));
    version.setReuseValidUntil(LocalDate.parse(reuseValidUntil));
    version.setReviewedAt(LocalDateTime.parse("2026-05-01T10:00:00").plusDays(id));
    return version;
  }

  private QuoteBomSupplementDetail detail(Long id, Long versionId) {
    QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
    detail.setId(id);
    detail.setSupplementVersionId(versionId);
    detail.setQuoteProductCode("MAT-001");
    detail.setSupplementScope("NON_BARE_FULL_BOM");
    detail.setLineNo(1);
    detail.setLevel(1);
    detail.setParentCode("MAT-001");
    detail.setMaterialCode("CHILD-001");
    detail.setMaterialName("补录子件");
    detail.setMaterialSpec("SPEC");
    detail.setMaterialModel("MODEL");
    detail.setDrawingNo("DRAW");
    detail.setShapeAttr("采购件");
    detail.setMainCategoryCode("1515601");
    detail.setQtyPerParent(new BigDecimal("2.00000000"));
    detail.setQtyPerTop(new BigDecimal("2.00000000"));
    detail.setParentBaseQty(new BigDecimal("1.00000000"));
    detail.setUnit("PCS");
    detail.setPath("/MAT-001/CHILD-001/");
    detail.setSortSeq(1);
    detail.setManualFlag(1);
    return detail;
  }
}
