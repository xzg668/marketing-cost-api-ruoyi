package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.QuoteBasePriceDetectResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.QuoteBasePriceMappingRule;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorQuoteBaseMappingMapper;
import com.sanhua.marketingcost.mapper.QuoteBasePriceMappingRuleMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteBasePriceMappingServiceImplTest {

  private QuoteBasePriceMappingRuleMapper ruleMapper;
  private FactorQuoteBaseMappingMapper mappingMapper;
  private FactorIdentityMapper factorIdentityMapper;
  private QuoteBasePriceMappingServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, QuoteBasePriceMappingRule.class);
    TableInfoHelper.initTableInfo(assistant, FactorQuoteBaseMapping.class);
    TableInfoHelper.initTableInfo(assistant, FactorIdentity.class);
  }

  @BeforeEach
  void setUp() {
    ruleMapper = mock(QuoteBasePriceMappingRuleMapper.class);
    mappingMapper = mock(FactorQuoteBaseMappingMapper.class);
    factorIdentityMapper = mock(FactorIdentityMapper.class);
    service = new QuoteBasePriceMappingServiceImpl(
        ruleMapper, mappingMapper, factorIdentityMapper, new ObjectMapper());
  }

  @Test
  @DisplayName("createRule：补默认值并写入规则")
  void createRuleFillsDefaultsAndInserts() {
    QuoteBasePriceMappingRule created = service.createRule(rule(null, null), "alice");

    assertThat(created.getBusinessUnitType()).isEqualTo("");
    assertThat(created.getMatchMode()).isEqualTo("ANY_KEYWORD");
    assertThat(created.getPriority()).isEqualTo(100);
    assertThat(created.getEnabled()).isEqualTo(1);
    assertThat(created.getDeleted()).isEqualTo(0);
    assertThat(created.getCreatedBy()).isEqualTo("alice");
    verify(ruleMapper).insert(created);
  }

  @Test
  @DisplayName("createRule：拒绝当前阶段未开放的 OA 基价字段")
  void createRuleRejectsUnsupportedQuoteField() {
    QuoteBasePriceMappingRule rule = rule("tin_price", null);

    assertThatThrownBy(() -> service.createRule(rule, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("当前阶段不支持的报价单字段");
  }

  @Test
  @DisplayName("createRule：关键词 JSON 必须至少包含一个非空关键词")
  void createRuleRejectsEmptyKeywords() {
    QuoteBasePriceMappingRule rule = rule(null, null);
    rule.setMatchKeywordsJson("[\"\", \"  \"]");

    assertThatThrownBy(() -> service.createRule(rule, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("匹配关键词不能为空");
  }

  @Test
  @DisplayName("updateRule：只合并传入字段并保留旧值")
  void updateRuleMergesPatch() {
    QuoteBasePriceMappingRule existing = rule(null, "old remark");
    existing.setId(10L);
    existing.setPriority(100);
    when(ruleMapper.selectById(10L)).thenReturn(existing);
    QuoteBasePriceMappingRule patch = new QuoteBasePriceMappingRule();
    patch.setPriority(20);
    patch.setRemark("new remark");

    QuoteBasePriceMappingRule updated = service.updateRule(10L, patch, "bob");

    assertThat(updated.getQuoteFieldCode()).isEqualTo("copper_price");
    assertThat(updated.getVariableCode()).isEqualTo("Cu");
    assertThat(updated.getPriority()).isEqualTo(20);
    assertThat(updated.getRemark()).isEqualTo("new remark");
    assertThat(updated.getUpdatedBy()).isEqualTo("bob");
    verify(ruleMapper).updateById(updated);
  }

  @Test
  @DisplayName("setRuleEnabled：停用规则后更新 enabled=0")
  void setRuleEnabledDisablesRule() {
    QuoteBasePriceMappingRule existing = rule(null, null);
    existing.setId(10L);
    when(ruleMapper.selectById(10L)).thenReturn(existing);

    service.setRuleEnabled(10L, false, "bob");

    assertThat(existing.getEnabled()).isZero();
    assertThat(existing.getUpdatedBy()).isEqualTo("bob");
    verify(ruleMapper).updateById(existing);
  }

  @Test
  @DisplayName("deleteRule：走 BaseMapper 逻辑删除入口")
  void deleteRuleUsesMapperDeleteById() {
    service.deleteRule(10L);

    verify(ruleMapper).deleteById(10L);
  }

  @Test
  @DisplayName("pageRules：按业务单元、字段、启用状态和关键词过滤")
  void pageRulesFiltersByRequestedFields() {
    Page<QuoteBasePriceMappingRule> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(List.of(rule(null, null)));
    when(ruleMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

    Page<QuoteBasePriceMappingRule> result =
        service.pageRules("COMMERCIAL", "copper_price", "铜", true, 1, 20);

    assertThat(result.getTotal()).isEqualTo(1);
    String sql = capturedRulePageSql();
    assertThat(sql).contains(
        "business_unit_type", "quote_field_code", "enabled",
        "quote_field_name", "variable_code", "match_keywords_json", "remark");
  }

  @Test
  @DisplayName("listEnabledRules：只取启用规则，业务单元规则和全局规则一起返回")
  void listEnabledRulesFiltersEnabledAndBusinessUnit() {
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule(null, null)));

    List<QuoteBasePriceMappingRule> rows = service.listEnabledRules("COMMERCIAL", "铜");

    assertThat(rows).hasSize(1);
    String sql = capturedRuleListSql();
    assertThat(sql).contains("enabled", "business_unit_type", "match_keywords_json");
  }

  @Test
  @DisplayName("saveMapping：补默认来源和置信度后新增识别结果")
  void saveMappingInsertsWithDefaults() {
    FactorQuoteBaseMapping mapping = mapping(null);

    FactorQuoteBaseMapping saved = service.saveMapping(mapping, "system");

    assertThat(saved.getMatchSource()).isEqualTo("AUTO");
    assertThat(saved.getConfidence()).isEqualTo("HIGH");
    assertThat(saved.getEnabled()).isEqualTo(1);
    assertThat(saved.getCreatedBy()).isEqualTo("system");
    verify(mappingMapper).insert(saved);
  }

  @Test
  @DisplayName("setMappingEnabled：停用识别结果后不再参与后续取价覆盖")
  void setMappingEnabledDisablesMapping() {
    FactorQuoteBaseMapping existing = mapping(99L);
    when(mappingMapper.selectById(99L)).thenReturn(existing);

    service.setMappingEnabled(99L, false, "system");

    assertThat(existing.getEnabled()).isZero();
    verify(mappingMapper).updateById(existing);
  }

  @Test
  @DisplayName("listMappingsByFactorIdentityId：按影响因素身份取启用映射")
  void listMappingsByFactorIdentityIdReturnsEnabledMappings() {
    when(mappingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mapping(1L)));

    List<FactorQuoteBaseMapping> rows = service.listMappingsByFactorIdentityId(191L);

    assertThat(rows).hasSize(1);
    String sql = capturedMappingListSql();
    assertThat(sql).contains("factor_identity_id", "enabled", "quote_field_code");
  }

  @Test
  @DisplayName("pageMappings：按影响因素身份、字段、启用状态和关键词过滤")
  void pageMappingsFiltersByRequestedFields() {
    Page<FactorQuoteBaseMapping> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(List.of(mapping(1L)));
    when(mappingMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

    Page<FactorQuoteBaseMapping> result =
        service.pageMappings(191L, "copper_price", "铜", true, 1, 20);

    assertThat(result.getTotal()).isEqualTo(1);
    ArgumentCaptor<Wrapper<FactorQuoteBaseMapping>> captor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(mappingMapper).selectPage(any(Page.class), captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment()).contains(
        "factor_identity_id", "quote_field_code", "enabled",
        "quote_field_name", "variable_code", "matched_keyword", "match_source");
  }

  @Test
  @DisplayName("deleteMapping：走 BaseMapper 逻辑删除入口")
  void deleteMappingUsesMapperDeleteById() {
    service.deleteMapping(99L);

    verify(mappingMapper).deleteById(99L);
  }

  @Test
  @DisplayName("detectAndSave：电解铜命中铜基价并写入映射")
  void detectAndSaveRecognizesCopper() {
    when(factorIdentityMapper.selectById(191L)).thenReturn(
        identity(191L, "COMMERCIAL", "上月长江现货1#电解铜均价", "电解铜", "平均价"));
    when(ruleMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(ruleWithKeywords(10L, "copper_price", "铜基价", "Cu",
            10, "[\"电解铜\",\"1#铜\"]")));

    QuoteBasePriceDetectResult result = service.detectAndSaveFactorQuoteBaseMapping(191L);

    assertThat(result.recognized()).isTrue();
    assertThat(result.getQuoteFieldCode()).isEqualTo("copper_price");
    assertThat(result.getMatchedKeyword()).isEqualTo("电解铜");
    ArgumentCaptor<FactorQuoteBaseMapping> captor =
        ArgumentCaptor.forClass(FactorQuoteBaseMapping.class);
    verify(mappingMapper).insert(captor.capture());
    assertThat(captor.getValue().getFactorIdentityId()).isEqualTo(191L);
    assertThat(captor.getValue().getVariableCode()).isEqualTo("Cu");
  }

  @Test
  @DisplayName("detectAndSave：A00铝命中铝基价")
  void detectAndSaveRecognizesA00Aluminum() {
    when(factorIdentityMapper.selectById(192L)).thenReturn(
        identity(192L, "COMMERCIAL", "上月长江现货A00铝锭均价", "A00铝", "平均价"));
    when(ruleMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(ruleWithKeywords(11L, "aluminum_price", "铝基价", "Al",
            30, "[\"A00铝\",\"AOO铝\"]")));

    QuoteBasePriceDetectResult result = service.detectAndSaveFactorQuoteBaseMapping(192L);

    assertThat(result.recognized()).isTrue();
    assertThat(result.getQuoteFieldCode()).isEqualTo("aluminum_price");
    assertThat(result.getMatchedKeyword()).isEqualTo("A00铝");
  }

  @Test
  @DisplayName("detectAndSave：长江现货市场AOO铝命中铝基价")
  void detectAndSaveRecognizesAooAluminum() {
    when(factorIdentityMapper.selectById(193L)).thenReturn(
        identity(193L, "COMMERCIAL", "长江现货市场AOO铝月均价", "长江现货市场AOO铝", "平均价"));
    when(ruleMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(ruleWithKeywords(12L, "aluminum_price", "铝基价", "Al",
            30, "[\"长江现货市场AOO铝\"]")));

    QuoteBasePriceDetectResult result = service.detectAndSaveFactorQuoteBaseMapping(193L);

    assertThat(result.recognized()).isTrue();
    assertThat(result.getQuoteFieldCode()).isEqualTo("aluminum_price");
  }

  @Test
  @DisplayName("detectAndSave：业务单元规则未命中时回退公共规则")
  void detectAndSaveFallsBackToGlobalRules() {
    when(factorIdentityMapper.selectById(194L)).thenReturn(
        identity(194L, "COMMERCIAL", "上月锌锭均价", "锌锭", "平均价"));
    when(ruleMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of())
        .thenReturn(List.of(ruleWithKeywords(13L, "zinc_price", "锌基价", "Zn",
            20, "[\"锌锭\"]")));

    QuoteBasePriceDetectResult result = service.detectAndSaveFactorQuoteBaseMapping(194L);

    assertThat(result.recognized()).isTrue();
    assertThat(result.getQuoteFieldCode()).isEqualTo("zinc_price");
  }

  @Test
  @DisplayName("detectAndSave：同优先级多规则命中时记冲突且不写映射")
  void detectAndSaveConflictDoesNotInsertMapping() {
    when(factorIdentityMapper.selectById(195L)).thenReturn(
        identity(195L, "COMMERCIAL", "电解铜 A00铝 混合描述", "电解铜A00铝", "平均价"));
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        ruleWithKeywords(14L, "copper_price", "铜基价", "Cu", 10, "[\"电解铜\"]"),
        ruleWithKeywords(15L, "aluminum_price", "铝基价", "Al", 10, "[\"A00铝\"]")));

    QuoteBasePriceDetectResult result = service.detectAndSaveFactorQuoteBaseMapping(195L);

    assertThat(result.conflict()).isTrue();
    verify(mappingMapper, org.mockito.Mockito.never()).insert(any(FactorQuoteBaseMapping.class));
  }

  @Test
  @DisplayName("detectAndSave：停用规则不参与命中")
  void detectAndSaveDisabledRuleDoesNotMatch() {
    when(factorIdentityMapper.selectById(196L)).thenReturn(
        identity(196L, "COMMERCIAL", "上月长江现货1#电解铜均价", "电解铜", "平均价"));
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    QuoteBasePriceDetectResult result = service.detectAndSaveFactorQuoteBaseMapping(196L);

    assertThat(result.unrecognized()).isTrue();
  }

  private String capturedRulePageSql() {
    ArgumentCaptor<Wrapper<QuoteBasePriceMappingRule>> captor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(ruleMapper).selectPage(any(Page.class), captor.capture());
    return captor.getValue().getCustomSqlSegment();
  }

  private String capturedRuleListSql() {
    ArgumentCaptor<Wrapper<QuoteBasePriceMappingRule>> captor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(ruleMapper).selectList(captor.capture());
    return captor.getValue().getCustomSqlSegment();
  }

  private String capturedMappingListSql() {
    ArgumentCaptor<Wrapper<FactorQuoteBaseMapping>> captor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(mappingMapper).selectList(captor.capture());
    return captor.getValue().getCustomSqlSegment();
  }

  private QuoteBasePriceMappingRule rule(String quoteFieldCode, String remark) {
    QuoteBasePriceMappingRule rule = new QuoteBasePriceMappingRule();
    rule.setBusinessUnitType("");
    rule.setQuoteFieldCode(quoteFieldCode == null ? "copper_price" : quoteFieldCode);
    rule.setQuoteFieldName("铜基价");
    rule.setVariableCode("Cu");
    rule.setMatchKeywordsJson("[\"铜\",\"铜基价\"]");
    rule.setRemark(remark);
    return rule;
  }

  private FactorQuoteBaseMapping mapping(Long id) {
    FactorQuoteBaseMapping mapping = new FactorQuoteBaseMapping();
    mapping.setId(id);
    mapping.setFactorIdentityId(191L);
    mapping.setRuleId(10L);
    mapping.setQuoteFieldCode("copper_price");
    mapping.setQuoteFieldName("铜基价");
    mapping.setVariableCode("Cu");
    return mapping;
  }

  private FactorIdentity identity(
      Long id, String businessUnitType, String factorName, String shortName, String priceSource) {
    FactorIdentity identity = new FactorIdentity();
    identity.setId(id);
    identity.setBusinessUnitType(businessUnitType);
    identity.setFactorName(factorName);
    identity.setShortName(shortName);
    identity.setPriceSource(priceSource);
    return identity;
  }

  private QuoteBasePriceMappingRule ruleWithKeywords(
      Long id,
      String quoteFieldCode,
      String quoteFieldName,
      String variableCode,
      int priority,
      String keywordsJson) {
    QuoteBasePriceMappingRule rule = new QuoteBasePriceMappingRule();
    rule.setId(id);
    rule.setBusinessUnitType("");
    rule.setQuoteFieldCode(quoteFieldCode);
    rule.setQuoteFieldName(quoteFieldName);
    rule.setVariableCode(variableCode);
    rule.setMatchKeywordsJson(keywordsJson);
    rule.setMatchMode("ANY_KEYWORD");
    rule.setPriority(priority);
    rule.setEnabled(1);
    rule.setDeleted(0);
    return rule;
  }
}
