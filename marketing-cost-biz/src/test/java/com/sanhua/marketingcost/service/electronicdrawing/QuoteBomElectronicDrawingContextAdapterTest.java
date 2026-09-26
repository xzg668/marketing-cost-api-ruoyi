package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.ingest.ResolvedCustomerKey;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteBomElectronicDrawingContextAdapterTest {
  private final OaFormItemMapper itemMapper = mock(OaFormItemMapper.class);
  private final OaFormMapper formMapper = mock(OaFormMapper.class);
  private final QuoteBomPreparationRecordMapper preparationMapper =
      mock(QuoteBomPreparationRecordMapper.class);
  private final QuoteBomStatusMapper statusMapper = mock(QuoteBomStatusMapper.class);
  private final QuoteBomSupplementVersionMapper versionMapper =
      mock(QuoteBomSupplementVersionMapper.class);
  private final QuoteBomContextResolver contextResolver = mock(QuoteBomContextResolver.class);
  private final BusinessChangeLogMapper changeLogMapper = mock(BusinessChangeLogMapper.class);
  private final QuoteBomElectronicDrawingContextAdapter adapter =
      new QuoteBomElectronicDrawingContextAdapter(
          itemMapper, formMapper, preparationMapper, statusMapper, versionMapper,
          contextResolver, changeLogMapper);

  @BeforeEach
  void setUp() {
    OaFormItem item = new OaFormItem();
    item.setId(10L);
    item.setOaFormId(1L);
    item.setMaterialNo("P-1");
    item.setProductName("产品");
    item.setBusinessUnitType("COMMERCIAL");
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-1");
    form.setBusinessUnitType("COMMERCIAL");
    when(itemMapper.selectById(10L)).thenReturn(item);
    when(formMapper.selectById(1L)).thenReturn(form);
    when(contextResolver.resolveWithExistingCostPeriod(any(), any(), eq("2026-08")))
        .thenReturn(context());
  }

  @Test
  void initialContextUsesLatestBomStatusMonthBeforePreparationExists() {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setCostPeriodMonth("2026-08");
    when(preparationMapper.selectList(any())).thenReturn(List.of());
    when(statusMapper.selectOne(any())).thenReturn(status);

    ElectronicDrawingWorkContext result = adapter.load(10L, "COMMERCIAL", "210", "2026-08");

    assertThat(result.workflowId()).isEqualTo(10L);
    assertThat(result.accountingMonth()).isEqualTo("2026-08");
    assertThat(result.preparationId()).isNull();
    verify(contextResolver).resolveWithExistingCostPeriod(any(), any(), eq("2026-08"));
  }

  @Test
  void activePreparationCarriesMappingStageAndOptimisticVersion() {
    QuoteBomPreparationRecord preparation = new QuoteBomPreparationRecord();
    preparation.setId(81L);
    preparation.setOaFormItemId(10L);
    preparation.setCostPeriodMonth("2026-08");
    preparation.setElectronicWorkflowVersion(5);
    preparation.setElectronicWorkflowStage("MAPPING_PENDING");
    preparation.setActiveFlag(1);
    when(preparationMapper.selectList(any())).thenReturn(List.of(preparation));

    ElectronicDrawingWorkContext result = adapter.load(10L, "COMMERCIAL", "210", "2026-08");

    assertThat(result.preparationId()).isEqualTo(81L);
    assertThat(result.revision()).isEqualTo(5);
    assertThat(result.workflowStage()).isEqualTo("MAPPING_PENDING");
  }

  private QuoteBomContext context() {
    return new QuoteBomContext(
        "2026-08", "P-1",
        new ResolvedCustomerKey(
            "客户A", ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER, null),
        "", new QuoteDataOrganization("210", "COMMERCIAL"));
  }
}
