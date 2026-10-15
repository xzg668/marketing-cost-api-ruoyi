package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.mapper.TechnicalPriceSourceMapper;
import com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope;
import com.sanhua.marketingcost.service.impl.MaterialPriceRouterServiceImpl;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialPriceRouterServiceTest {
  private final MaterialPriceTypeMapper configs = mock(MaterialPriceTypeMapper.class);
  private final MaterialMasterMapper masters = mock(MaterialMasterMapper.class);
  private final TechnicalPriceSourceMapper sources = mock(TechnicalPriceSourceMapper.class);
  private final MaterialPricePriorityService priorities = new MaterialPricePriorityService(configs, mock(org.springframework.transaction.PlatformTransactionManager.class));
  private final com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector months = mock(com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector.class);
  private final MaterialPriceRouterService router = new MaterialPriceRouterServiceImpl(priorities, masters, sources, months);
  private final LocalDate date = LocalDate.of(2026,10,13);
  private final Scope scope = new Scope("COMMERCIAL","2026-10","210","M1");

  @Test void batchUsesTheSameDecisionRulesAndRereadsOnTheNextCall() {
    var fixed = new MaterialMaster(); fixed.setMaterialCode("M1"); fixed.setShapeAttr("外购件");
    var make = new MaterialMaster(); make.setMaterialCode("M2"); make.setShapeAttr("制造件");
    when(masters.selectList(any())).thenReturn(List.of(fixed, make));
    when(sources.publicKindsBatch(any(), eq("2026-10"), isNull(), eq("COMMERCIAL"), eq("210"), eq(date)))
        .thenReturn(List.of(new TechnicalPriceSourceMapper.MaterialKind("M1", "FIXED")))
        .thenReturn(List.of(new TechnicalPriceSourceMapper.MaterialKind("M1", "LINKED")));
    var before = router.listCandidatesBatch(List.of("M2", "M1", "M1"), "2026-10", date, "210", "COMMERCIAL");
    assertThat(before.get("M1").getFirst().priceType()).isEqualTo(PriceTypeEnum.FIXED);
    assertThat(before.get("M2").getFirst().priceType()).isEqualTo(PriceTypeEnum.MAKE);
    var after = router.listCandidatesBatch(List.of("M1", "M2"), "2026-10", date, "210", "COMMERCIAL");
    assertThat(after.get("M1").getFirst().priceType()).isEqualTo(PriceTypeEnum.LINKED);
    verify(sources, times(2)).publicKindsBatch(eq(List.of("M1", "M2")), any(), any(), any(), any(), any());
    verify(sources, never()).publicKinds(any(), any(), any(), any(), any(), any());
    verify(configs, never()).insertDefaults(any(), any(), any());
  }

  @Test void defaultOrderUsesDistinctSettlementFixedAndNeverInventsSettlementLinkedSource() {
    when(sources.publicKinds("M1","2026-10",null,"COMMERCIAL","210",date))
        .thenReturn(List.of("FIXED","SETTLE_FIXED","RANGE","LINKED","SETTLE_LINKED"));
    assertThat(candidates()).filteredOn(route -> !route.supplemental())
        .extracting(PriceTypeRoute::rawPriceType).containsExactly("联动价","区间价","结算固定价","固定价");
    verify(configs,never()).insertDefaults(any(),any(),any());
  }

  @Test void financeCanPreferFixedOverLinkedAndRangeForThisMonthAndOrganization() {
    var rows = MaterialPriceSourceType.DEFAULT_ORDER.stream().map(type -> {
      var row = new MaterialPriceType();
      row.setPriceType(type.name());
      row.setPriority(type.getDefaultPriority());
      row.setManualPriority(6-type.getDefaultPriority());
      return row;
    }).toList();
    when(configs.selectScope(scope,false)).thenReturn(rows);
    when(sources.publicKinds("M1","2026-10",null,"COMMERCIAL","210",date)).thenReturn(List.of("LINKED","RANGE","FIXED"));
    assertThat(candidates()).filteredOn(route -> !route.supplemental())
        .extracting(PriceTypeRoute::rawPriceType).containsExactly("固定价","区间价","联动价");
    verify(configs).selectScope(scope,false);
    verify(sources).publicKinds("M1","2026-10",null,"COMMERCIAL","210",date);
  }

  @Test void actualPreparationInitializesOnceWhileReadOnlyRecognitionDoesNotWrite() {
    router.prepareCandidates("M1","料号1","2026-10",date,"210","COMMERCIAL");
    candidates();
    verify(configs,times(1)).insertDefaults(scope,"料号1",MaterialPriceSourceType.DEFAULT_ORDER);
  }

  @Test void noPublicSourcesOnlyAcceptsRealPublishedSupplementalPrice() {
    assertThat(candidates()).isEmpty();
    when(sources.supplemental("M1","COMMERCIAL"))
        .thenReturn(List.of(new TechnicalPriceSourceMapper.Source("FIXED",42L)));
    assertThat(candidates()).singleElement().satisfies(route -> {
      assertThat(route.supplemental()).isTrue();
      assertThat(route.sourceRecordId()).isEqualTo(42L);
      assertThat(route.priorityConfiguration().scope()).isEqualTo(scope);
    });
  }

  @Test void publicSourcesKeepTechnicalSupplementalAfterPublicCandidates() {
    when(sources.publicKinds("M1","2026-10",null,"COMMERCIAL","210",date)).thenReturn(List.of("FIXED"));
    assertThat(candidates()).extracting(PriceTypeRoute::supplemental).containsExactly(false,true,true);
    verify(sources,never()).supplemental(any(),any());
  }

  @Test void duplicatedPublishedSupplementalSourcesFailExplicitly() {
    when(sources.supplemental("M1","COMMERCIAL")).thenReturn(List.of(
        new TechnicalPriceSourceMapper.Source("FIXED",1L),new TechnicalPriceSourceMapper.Source("LINKED",2L)));
    assertThatThrownBy(this::candidates).isInstanceOf(IllegalStateException.class).hasMessageContaining("重复");
  }

  @Test void manufacturedMaterialCanStillUseBomCalculation() {
    var master = new MaterialMaster();master.setShapeAttr("制造件");
    when(masters.selectOne(any())).thenReturn(master);
    assertThat(candidates()).singleElement().extracting(PriceTypeRoute::priceType).isEqualTo(PriceTypeEnum.MAKE);
  }

  private List<PriceTypeRoute> candidates() {
    return router.listCandidates("M1","2026-10",date,"210","COMMERCIAL");
  }
}
