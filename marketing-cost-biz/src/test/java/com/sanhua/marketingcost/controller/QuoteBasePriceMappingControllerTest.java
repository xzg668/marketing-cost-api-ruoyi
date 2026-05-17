package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.EnabledUpdateRequest;
import com.sanhua.marketingcost.dto.FactorQuoteBaseMappingPageResponse;
import com.sanhua.marketingcost.dto.FactorQuoteBaseMappingRequest;
import com.sanhua.marketingcost.dto.QuoteBasePriceMappingRulePageResponse;
import com.sanhua.marketingcost.dto.QuoteBasePriceMappingRuleRequest;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.QuoteBasePriceMappingRule;
import com.sanhua.marketingcost.service.QuoteBasePriceMappingService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class QuoteBasePriceMappingControllerTest {

  private QuoteBasePriceMappingService service;
  private QuoteBasePriceMappingController controller;

  @BeforeEach
  void setUp() {
    service = mock(QuoteBasePriceMappingService.class);
    controller = new QuoteBasePriceMappingController(service, new ObjectMapper());
  }

  @Test
  @DisplayName("GET /quote-base-mapping-rules：分页参数透传")
  void listRules() {
    Page<QuoteBasePriceMappingRule> page = new Page<>(2, 30);
    page.setTotal(1);
    page.setRecords(List.of(rule(10L)));
    when(service.pageRules("COMMERCIAL", "copper_price", "铜", true, 2, 30))
        .thenReturn(page);

    CommonResult<QuoteBasePriceMappingRulePageResponse> result =
        controller.listRules("COMMERCIAL", "copper_price", "铜", true, 2, 30);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1);
    assertThat(result.getData().getRecords()).hasSize(1);
    verify(service).pageRules("COMMERCIAL", "copper_price", "铜", true, 2, 30);
  }

  @Test
  @DisplayName("POST /quote-base-mapping-rules：前端关键词数组转 JSON 后新增")
  void createRule() {
    when(service.createRule(any(QuoteBasePriceMappingRule.class), eq("alice")))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CommonResult<QuoteBasePriceMappingRule> result =
        controller.createRule(ruleRequest(), auth("alice"));

    assertThat(result.isSuccess()).isTrue();
    ArgumentCaptor<QuoteBasePriceMappingRule> captor =
        ArgumentCaptor.forClass(QuoteBasePriceMappingRule.class);
    verify(service).createRule(captor.capture(), eq("alice"));
    assertThat(captor.getValue().getMatchKeywordsJson()).contains("电解铜", "1#铜");
  }

  @Test
  @DisplayName("PUT /quote-base-mapping-rules/{id}：修改规则透传 id 和操作者")
  void updateRule() {
    QuoteBasePriceMappingRule updated = rule(10L);
    when(service.updateRule(eq(10L), any(QuoteBasePriceMappingRule.class), eq("bob")))
        .thenReturn(updated);

    CommonResult<QuoteBasePriceMappingRule> result =
        controller.updateRule(10L, ruleRequest(), auth("bob"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(updated);
    verify(service).updateRule(eq(10L), any(QuoteBasePriceMappingRule.class), eq("bob"));
  }

  @Test
  @DisplayName("PUT /quote-base-mapping-rules/{id}/enabled：停用规则")
  void setRuleEnabled() {
    EnabledUpdateRequest request = new EnabledUpdateRequest();
    request.setEnabled(false);

    CommonResult<Void> result = controller.setRuleEnabled(10L, request, auth("bob"));

    assertThat(result.isSuccess()).isTrue();
    verify(service).setRuleEnabled(10L, false, "bob");
  }

  @Test
  @DisplayName("DELETE /quote-base-mapping-rules/{id}：删除规则")
  void deleteRule() {
    CommonResult<Void> result = controller.deleteRule(10L);

    assertThat(result.isSuccess()).isTrue();
    verify(service).deleteRule(10L);
  }

  @Test
  @DisplayName("GET /factor-quote-base-mappings：分页参数透传")
  void listMappings() {
    Page<FactorQuoteBaseMapping> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(List.of(mapping(1L)));
    when(service.pageMappings(191L, "copper_price", "铜", true, 1, 20))
        .thenReturn(page);

    CommonResult<FactorQuoteBaseMappingPageResponse> result =
        controller.listMappings(191L, "copper_price", "铜", true, 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1);
    verify(service).pageMappings(191L, "copper_price", "铜", true, 1, 20);
  }

  @Test
  @DisplayName("PUT /factor-quote-base-mappings/{id}：人工修正映射")
  void updateMapping() {
    FactorQuoteBaseMapping saved = mapping(99L);
    when(service.saveMapping(any(FactorQuoteBaseMapping.class), eq("alice"))).thenReturn(saved);

    CommonResult<FactorQuoteBaseMapping> result =
        controller.updateMapping(99L, mappingRequest(), auth("alice"));

    assertThat(result.isSuccess()).isTrue();
    ArgumentCaptor<FactorQuoteBaseMapping> captor =
        ArgumentCaptor.forClass(FactorQuoteBaseMapping.class);
    verify(service).saveMapping(captor.capture(), eq("alice"));
    assertThat(captor.getValue().getId()).isEqualTo(99L);
    assertThat(captor.getValue().getFactorIdentityId()).isEqualTo(191L);
  }

  @Test
  @DisplayName("规则维护接口带写权限，普通列表权限不能维护规则")
  void writeEndpointsRequireEditPermission() throws Exception {
    Method create = QuoteBasePriceMappingController.class.getMethod(
        "createRule", QuoteBasePriceMappingRuleRequest.class,
        org.springframework.security.core.Authentication.class);
    Method update = QuoteBasePriceMappingController.class.getMethod(
        "updateRule", Long.class, QuoteBasePriceMappingRuleRequest.class,
        org.springframework.security.core.Authentication.class);

    assertThat(create.getAnnotation(PreAuthorize.class).value())
        .contains("price:quote-base-mapping:add", "price:quote-base-mapping:edit")
        .doesNotContain("price:quote-base-mapping:list')");
    assertThat(update.getAnnotation(PreAuthorize.class).value())
        .contains("price:quote-base-mapping:edit")
        .doesNotContain("price:quote-base-mapping:list')");
  }

  private UsernamePasswordAuthenticationToken auth(String name) {
    return new UsernamePasswordAuthenticationToken(name, "n/a");
  }

  private QuoteBasePriceMappingRuleRequest ruleRequest() {
    QuoteBasePriceMappingRuleRequest request = new QuoteBasePriceMappingRuleRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setQuoteFieldCode("copper_price");
    request.setQuoteFieldName("铜基价");
    request.setVariableCode("Cu");
    request.setMatchKeywords(List.of("电解铜", "1#铜"));
    request.setPriority(10);
    request.setEnabled(1);
    return request;
  }

  private FactorQuoteBaseMappingRequest mappingRequest() {
    FactorQuoteBaseMappingRequest request = new FactorQuoteBaseMappingRequest();
    request.setFactorIdentityId(191L);
    request.setRuleId(10L);
    request.setQuoteFieldCode("copper_price");
    request.setQuoteFieldName("铜基价");
    request.setVariableCode("Cu");
    request.setMatchedKeyword("电解铜");
    request.setMatchSource("MANUAL");
    request.setConfidence("HIGH");
    request.setEnabled(1);
    return request;
  }

  private QuoteBasePriceMappingRule rule(Long id) {
    QuoteBasePriceMappingRule rule = new QuoteBasePriceMappingRule();
    rule.setId(id);
    rule.setQuoteFieldCode("copper_price");
    rule.setQuoteFieldName("铜基价");
    rule.setVariableCode("Cu");
    rule.setMatchKeywordsJson("[\"电解铜\"]");
    return rule;
  }

  private FactorQuoteBaseMapping mapping(Long id) {
    FactorQuoteBaseMapping mapping = new FactorQuoteBaseMapping();
    mapping.setId(id);
    mapping.setFactorIdentityId(191L);
    mapping.setQuoteFieldCode("copper_price");
    mapping.setQuoteFieldName("铜基价");
    mapping.setVariableCode("Cu");
    return mapping;
  }
}
