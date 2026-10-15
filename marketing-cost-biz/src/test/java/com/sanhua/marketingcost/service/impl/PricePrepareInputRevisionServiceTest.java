package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.CostInputRevisionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PricePrepareInputRevisionServiceTest {
  private OaFormMapper forms;
  private OaFormItemMapper items;
  private PricePrepareBatchMapper batches;
  private CostInputRevisionService revisions;
  private PricePrepareInputRevisionService service;
  private PricePrepareBatch batch;

  @BeforeEach
  void setUp() {
    var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareBatch.class);
    forms = mock(OaFormMapper.class);
    items = mock(OaFormItemMapper.class);
    batches = mock(PricePrepareBatchMapper.class);
    revisions = mock(CostInputRevisionService.class);
    service = new PricePrepareInputRevisionService(forms, items, batches, revisions);
    var form = new OaForm();
    form.setId(1L);
    var item = new OaFormItem();
    item.setOaFormId(1L);
    when(forms.selectOne(any())).thenReturn(form);
    when(items.selectById(2L)).thenReturn(item);
    when(revisions.currentRevision(form, item, "2026-10")).thenReturn("actual-revision");
    batch = new PricePrepareBatch();
    when(batches.selectOne(any())).thenReturn(batch);
    when(batches.update(any(), any())).thenReturn(1);
  }

  @Test
  void bindsBothPriceSidesToTheRevisionSeenInThePreparationTransaction() {
    assertThat(service.bind("OA-1", 2L, "2026-10", "P1", "P0")).isEqualTo("actual-revision");
    var updates =
        ArgumentCaptor.forClass(
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
    verify(batches, times(2)).update(any(), updates.capture());
    assertThat(updates.getAllValues())
        .allSatisfy(
            update -> {
              assertThat(update.getParamNameValuePairs().values()).contains("actual-revision");
            });
  }

  @Test
  void preventsReusedOldPricesBeingRelabelledWithNewInputs() {
    batch.setSourceRevision("previous-revision");
    assertThatThrownBy(() -> service.bind("OA-1", 2L, "2026-10", "P1", "P0"))
        .isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
    verify(batches, never()).update(any(), any());
  }

  @Test
  void rejectsAProductFromAnotherQuotationBeforeWriting() {
    var item = new OaFormItem();
    item.setOaFormId(3L);
    when(items.selectById(2L)).thenReturn(item);
    assertThatThrownBy(() -> service.bind("OA-1", 2L, "2026-10", "P1", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("归属无效");
    verifyNoInteractions(revisions, batches);
  }
}
