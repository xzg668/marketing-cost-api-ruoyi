package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapRevokeRequest;
import com.sanhua.marketingcost.entity.MakePartNoScrapConfirmation;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.MakePartNoScrapConfirmationMapper;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MakePartNoScrapConfirmationServiceImplTest {

  private MakePartNoScrapConfirmationMapper mapper;
  private SysOperationLogMapper operationLogMapper;
  private MakePartNoScrapConfirmationServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, MakePartNoScrapConfirmation.class);
  }

  @BeforeEach
  void setUp() {
    mapper = mock(MakePartNoScrapConfirmationMapper.class);
    operationLogMapper = mock(SysOperationLogMapper.class);
    service = new MakePartNoScrapConfirmationServiceImpl(mapper, operationLogMapper, new ObjectMapper());
  }

  @Test
  @DisplayName("confirm：正常确认写入 ACTIVE 记录并返回确认人")
  void confirmCreatesActiveRecord() {
    when(mapper.selectList(any())).thenReturn(List.of());
    when(mapper.insert(any(MakePartNoScrapConfirmation.class))).thenAnswer(invocation -> {
      MakePartNoScrapConfirmation entity = invocation.getArgument(0);
      entity.setId(10L);
      return 1;
    });

    NoScrapConfirmResponse response = service.confirm(confirmRequest(), "alice");

    assertThat(response.getId()).isEqualTo(10L);
    assertThat(response.getMaterialNo()).isEqualTo("301300339");
    assertThat(response.getStatus()).isEqualTo(MakePartNoScrapConfirmation.STATUS_ACTIVE);
    assertThat(response.getConfirmedBy()).isEqualTo("alice");
    ArgumentCaptor<MakePartNoScrapConfirmation> captor =
        ArgumentCaptor.forClass(MakePartNoScrapConfirmation.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getConfirmReason()).isEqualTo("确认该料号确实无废料产生");
    assertThat(captor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
  }

  @Test
  @DisplayName("confirm/revoke：操作日志包含料号、OA、确认原因和撤销原因")
  void operationLogContainsAuditFields() {
    when(mapper.selectList(any())).thenReturn(List.of());
    when(mapper.insert(any(MakePartNoScrapConfirmation.class))).thenAnswer(invocation -> {
      MakePartNoScrapConfirmation entity = invocation.getArgument(0);
      entity.setId(10L);
      return 1;
    });

    service.confirm(confirmRequest(), "alice");

    ArgumentCaptor<SysOperationLog> confirmLogCaptor =
        ArgumentCaptor.forClass(SysOperationLog.class);
    verify(operationLogMapper).insert(confirmLogCaptor.capture());
    SysOperationLog confirmLog = confirmLogCaptor.getValue();
    assertThat(confirmLog.getTitle()).isEqualTo("价格准备无废料确认");
    assertThat(confirmLog.getOperName()).isEqualTo("alice");
    assertThat(confirmLog.getOperParam()).contains(
        "301300339",
        "FI-SC-006-20260108-109",
        "确认该料号确实无废料产生");

    MakePartNoScrapConfirmation existing = active("2026-06", null);
    existing.setId(10L);
    existing.setSourceOaNo("FI-SC-006-20260108-109");
    when(mapper.selectById(10L)).thenReturn(existing);
    NoScrapRevokeRequest revokeRequest = new NoScrapRevokeRequest();
    revokeRequest.setRevokeReason("重新确认需要补充正式废料映射");

    service.revoke(10L, revokeRequest, "bob");

    ArgumentCaptor<SysOperationLog> allLogs = ArgumentCaptor.forClass(SysOperationLog.class);
    verify(operationLogMapper, org.mockito.Mockito.times(2)).insert(allLogs.capture());
    SysOperationLog revokeLog = allLogs.getAllValues().get(1);
    assertThat(revokeLog.getOperName()).isEqualTo("bob");
    assertThat(revokeLog.getOperParam()).contains(
        "301300339",
        "FI-SC-006-20260108-109",
        "重新确认需要补充正式废料映射");
  }

  @Test
  @DisplayName("confirm：同一事业部料号有效期重叠时拒绝重复确认")
  void confirmRejectsOverlappingActiveRecord() {
    when(mapper.selectList(any())).thenReturn(List.of(active("2026-05", "2026-07")));
    NoScrapConfirmRequest request = confirmRequest();
    request.setEffectiveFromMonth("2026-06");

    assertThatThrownBy(() -> service.confirm(request, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已存在人工确认无废料记录");
    verify(mapper, never()).insert(any(MakePartNoScrapConfirmation.class));
  }

  @Test
  @DisplayName("revoke + effective：撤销后有效查询不命中")
  void revokedRecordIsNotEffective() {
    MakePartNoScrapConfirmation existing = active("2026-06", null);
    existing.setId(11L);
    when(mapper.selectById(11L)).thenReturn(existing);
    NoScrapRevokeRequest request = new NoScrapRevokeRequest();
    request.setRevokeReason("业务重新确认需要废料映射");

    NoScrapConfirmResponse revoked = service.revoke(11L, request, "bob");

    assertThat(revoked.getStatus()).isEqualTo(MakePartNoScrapConfirmation.STATUS_REVOKED);
    assertThat(revoked.getRevokedBy()).isEqualTo("bob");
    verify(mapper).updateById(existing);

    when(mapper.selectList(any())).thenReturn(List.of(existing));
    assertThat(service.findEffective("301300339", "2026-06", "COMMERCIAL")).isNull();
  }

  @Test
  @DisplayName("effective：前一月不命中、开始月命中、结束月后不命中")
  void effectiveQueryHonorsMonthBoundaries() {
    when(mapper.selectList(any())).thenReturn(List.of(active("2026-05", "2026-07")));

    assertThat(service.findEffective("301300339", "2026-04", "COMMERCIAL")).isNull();
    assertThat(service.findEffective("301300339", "2026-05", "COMMERCIAL")).isNotNull();
    assertThat(service.findEffective("301300339", "2026-08", "COMMERCIAL")).isNull();
  }

  @Test
  @DisplayName("confirm：原因空或太短时失败")
  void confirmRejectsBlankOrShortReason() {
    NoScrapConfirmRequest blank = confirmRequest();
    blank.setConfirmReason("   ");
    assertThatThrownBy(() -> service.confirm(blank, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("confirmReason 不能为空");

    NoScrapConfirmRequest shortReason = confirmRequest();
    shortReason.setConfirmReason("无废料");
    assertThatThrownBy(() -> service.confirm(shortReason, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能少于5个字符");
    verify(mapper, never()).insert(any(MakePartNoScrapConfirmation.class));
  }

  @Test
  @DisplayName("confirm：结束月早于开始月时失败")
  void confirmRejectsInvalidMonthRange() {
    NoScrapConfirmRequest request = confirmRequest();
    request.setEffectiveFromMonth("2026-07");
    request.setEffectiveToMonth("2026-06");

    assertThatThrownBy(() -> service.confirm(request, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能早于");
  }

  @Test
  @DisplayName("page：分页查询返回响应 DTO")
  void pageReturnsResponseDto() {
    when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
      Page<MakePartNoScrapConfirmation> page = invocation.getArgument(0);
      page.setTotal(1);
      page.setRecords(List.of(active("2026-06", null)));
      return page;
    });

    NoScrapConfirmationPageRequest request = new NoScrapConfirmationPageRequest();
    request.setMaterialNo("301300339");
    request.setPage(0);
    request.setPageSize(999);
    NoScrapConfirmationPageResponse response = service.page(request);

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getRecords()).hasSize(1);
    assertThat(response.getRecords().get(0).getMaterialNo()).isEqualTo("301300339");
  }

  private NoScrapConfirmRequest confirmRequest() {
    NoScrapConfirmRequest request = new NoScrapConfirmRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setMaterialNo("301300339");
    request.setMaterialName("测试料号");
    request.setEffectiveFromMonth("2026-06");
    request.setConfirmReason("确认该料号确实无废料产生");
    request.setSourceOaNo("FI-SC-006-20260108-109");
    request.setSourceGapId(301300339L);
    return request;
  }

  private MakePartNoScrapConfirmation active(String from, String to) {
    MakePartNoScrapConfirmation row = new MakePartNoScrapConfirmation();
    row.setId(1L);
    row.setBusinessUnitType("COMMERCIAL");
    row.setMaterialNo("301300339");
    row.setMaterialName("测试料号");
    row.setEffectiveFromMonth(from);
    row.setEffectiveToMonth(to);
    row.setStatus(MakePartNoScrapConfirmation.STATUS_ACTIVE);
    row.setConfirmReason("确认该料号确实无废料产生");
    row.setConfirmedBy("alice");
    row.setConfirmedAt(LocalDateTime.of(2026, 6, 2, 10, 0));
    return row;
  }
}
