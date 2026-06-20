package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.CostRunResultService;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CostRunResultWriterImplTest {

  @Test
  void writesQuoteResultAndDetailsIdempotently() {
    FakeResultService resultService = new FakeResultService();
    ResultMapperState resultState = new ResultMapperState();
    PartMapperState partState = new PartMapperState();
    CostMapperState costState = new CostMapperState();
    FakeTraceSnapshotService traceSnapshotService = new FakeTraceSnapshotService();
    CostRunResultWriterImpl writer =
        new CostRunResultWriterImpl(
            resultService,
            resultState.proxy(),
            partState.proxy(),
            costState.proxy(),
            pricePrepareItemMapper(),
            traceSnapshotService);
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    OaFormItem item = new OaFormItem();
    item.setMaterialNo("P-001");
    CostRunObjectResult result = result();

    writer.writeQuoteResult(result, form, item);
    writer.writeQuoteResult(result, form, item);

    assertThat(resultService.totalCostUpdates).containsExactly(new BigDecimal("88.123456"), new BigDecimal("88.123456"));
    assertThat(resultService.savedItems).containsExactly(item, item);
    assertThat(partState.rows).hasSize(1);
    assertThat(partState.rows.get(0).getOaNo()).isEqualTo("OA-001");
    assertThat(partState.rows.get(0).getProductCode()).isEqualTo("P-001");
    assertThat(partState.rows.get(0).getPartCode()).isEqualTo("PART-001");
    assertThat(partState.rows.get(0).getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(costState.rows).hasSize(1);
    assertThat(costState.rows.get(0).getCostCode()).isEqualTo("TOTAL");
    assertThat(costState.rows.get(0).getLineNo()).isEqualTo(1);
    assertThat(costState.rows.get(0).getAmount()).isEqualByComparingTo("88.123456");
    assertThat(traceSnapshotService.rebuilt).isEmpty();
  }

  @Test
  void versionedQuoteResultWritesVersionFieldsAndKeepsOtherProductLineRows() {
    FakeResultService resultService = new FakeResultService();
    ResultMapperState resultState = new ResultMapperState();
    PartMapperState partState = new PartMapperState();
    CostMapperState costState = new CostMapperState();
    FakeTraceSnapshotService traceSnapshotService = new FakeTraceSnapshotService();
    CostRunResultWriterImpl writer =
        new CostRunResultWriterImpl(
            resultService,
            resultState.proxy(),
            partState.proxy(),
            costState.proxy(),
            pricePrepareItemMapper(),
            traceSnapshotService);
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    OaFormItem item = new OaFormItem();
    item.setId(101L);
    item.setMaterialNo("P-SAME");
    CostRunObjectResult first = result("RUN-101", 1001L, 101L);
    CostRunObjectResult second = result("RUN-102", 1002L, 102L);

    writer.writeQuoteResult(first, form, item);
    writer.writeQuoteResult(second, form, item);

    assertThat(resultService.savedItems).isEmpty();
    assertThat(resultState.rows).hasSize(2);
    assertThat(resultState.rows)
        .extracting(CostRunResult::getCostRunNo)
        .containsExactlyInAnyOrder("RUN-101", "RUN-102");
    assertThat(resultState.rows.get(0).getCostRunVersionId()).isEqualTo(1001L);
    assertThat(resultState.rows.get(0).getOaFormItemId()).isEqualTo(101L);
    assertThat(resultState.rows.get(0).getPricePrepareNo()).isEqualTo("PPR-101");
    assertThat(partState.rows).hasSize(2);
    assertThat(partState.rows)
        .extracting(CostRunPartItem::getCostRunNo)
        .containsExactlyInAnyOrder("RUN-101", "RUN-102");
    assertThat(partState.rows)
        .extracting(CostRunPartItem::getBomRowId)
        .containsOnly(501L);
    assertThat(partState.rows)
        .extracting(CostRunPartItem::getPricePrepareItemId)
        .containsOnly(9501L);
    assertThat(costState.rows).hasSize(2);
    assertThat(costState.rows)
        .extracting(CostRunCostItem::getCostRunNo)
        .containsExactlyInAnyOrder("RUN-101", "RUN-102");
    assertThat(traceSnapshotService.rebuilt)
        .extracting(QuoteCostRunVersion::getCostRunNo)
        .containsExactly("RUN-101", "RUN-102");
    assertThat(traceSnapshotService.rebuilt)
        .extracting(QuoteCostRunVersion::getId)
        .containsExactly(1001L, 1002L);
  }

  @Test
  void traceSnapshotFailureDoesNotBreakVersionedResultWriting() {
    FakeResultService resultService = new FakeResultService();
    ResultMapperState resultState = new ResultMapperState();
    PartMapperState partState = new PartMapperState();
    CostMapperState costState = new CostMapperState();
    FakeTraceSnapshotService traceSnapshotService = new FakeTraceSnapshotService();
    traceSnapshotService.fail = true;
    CostRunResultWriterImpl writer =
        new CostRunResultWriterImpl(
            resultService,
            resultState.proxy(),
            partState.proxy(),
            costState.proxy(),
            pricePrepareItemMapper(),
            traceSnapshotService);
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    OaFormItem item = new OaFormItem();
    item.setId(101L);
    item.setMaterialNo("P-SAME");

    assertThatCode(() -> writer.writeQuoteResult(result("RUN-FAIL", 1003L, 101L), form, item))
        .doesNotThrowAnyException();

    assertThat(resultState.rows).hasSize(1);
    assertThat(partState.rows).hasSize(1);
    assertThat(costState.rows).hasSize(1);
    assertThat(traceSnapshotService.rebuilt)
        .extracting(QuoteCostRunVersion::getCostRunNo)
        .containsExactly("RUN-FAIL");
  }

  @Test
  void traceSnapshotRebuildRunsAfterTransactionCommit() {
    FakeResultService resultService = new FakeResultService();
    ResultMapperState resultState = new ResultMapperState();
    PartMapperState partState = new PartMapperState();
    CostMapperState costState = new CostMapperState();
    FakeTraceSnapshotService traceSnapshotService = new FakeTraceSnapshotService();
    CostRunResultWriterImpl writer =
        new CostRunResultWriterImpl(
            resultService,
            resultState.proxy(),
            partState.proxy(),
            costState.proxy(),
            pricePrepareItemMapper(),
            traceSnapshotService);
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    OaFormItem item = new OaFormItem();
    item.setId(101L);
    item.setMaterialNo("P-SAME");

    TransactionSynchronizationManager.initSynchronization();
    try {
      writer.writeQuoteResult(result("RUN-AFTER-COMMIT", 1004L, 101L), form, item);

      assertThat(resultState.rows).hasSize(1);
      assertThat(partState.rows).hasSize(1);
      assertThat(costState.rows).hasSize(1);
      assertThat(traceSnapshotService.rebuilt).isEmpty();

      for (TransactionSynchronization synchronization :
          TransactionSynchronizationManager.getSynchronizations()) {
        synchronization.afterCommit();
      }
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    assertThat(traceSnapshotService.rebuilt)
        .extracting(QuoteCostRunVersion::getCostRunNo)
        .containsExactly("RUN-AFTER-COMMIT");
  }

  @Test
  void truncatesLongRemarksBeforeWritingCostItems() {
    FakeResultService resultService = new FakeResultService();
    ResultMapperState resultState = new ResultMapperState();
    PartMapperState partState = new PartMapperState();
    CostMapperState costState = new CostMapperState();
    CostRunResultWriterImpl writer =
        new CostRunResultWriterImpl(
            resultService,
            resultState.proxy(),
            partState.proxy(),
            costState.proxy(),
            pricePrepareItemMapper(),
            new FakeTraceSnapshotService());
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    OaFormItem item = new OaFormItem();
    item.setMaterialNo("P-001");
    CostRunCostItemDto costItem = totalCost("88.123456");
    costItem.setRemark("缺价".repeat(3000));
    CostRunObjectResult result =
        CostRunObjectResult.of(
            CostRunContext.quote("OA-001", 1L, "P-001", null, "客户A", "COMMERCIAL", "2026-05", "OBJ-1"),
            null,
            null,
            List.of(),
            List.of(costItem));

    writer.writeQuoteResult(result, form, item);

    assertThat(costState.rows).hasSize(1);
    assertThat(costState.rows.get(0).getRemark()).hasSize(4000);
    assertThat(costState.rows.get(0).getRemark()).endsWith("...(truncated)");
  }

  private CostRunObjectResult result() {
    return CostRunObjectResult.of(
        CostRunContext.quote("OA-001", 1L, "P-001", null, "客户A", "COMMERCIAL", "2026-05", "OBJ-1"),
        null,
        null,
        List.of(partItem()),
        List.of(totalCost("88.123456")));
  }

  private CostRunObjectResult result(String costRunNo, Long versionId, Long oaFormItemId) {
    CostRunContext context =
        CostRunContext.quote(
            "OA-001",
            oaFormItemId,
            "P-SAME",
            null,
            "客户A",
            "COMMERCIAL",
            "2026-05",
            "OBJ-" + oaFormItemId);
    context.setCostRunNo(costRunNo);
    context.setCostRunVersionId(versionId);
    context.setPricePrepareNo("PPR-" + oaFormItemId);
    return CostRunObjectResult.of(
        context,
        null,
        null,
        List.of(partItem()),
        List.of(totalCost("88.123456")));
  }

  private CostRunPartItemDto partItem() {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo("OA-001");
    item.setProductCode("P-001");
    item.setPartCode("PART-001");
    item.setBomRowId(501L);
    item.setPartName("Part");
    item.setPartQty(new BigDecimal("2"));
    item.setUnitPrice(new BigDecimal("3"));
    item.setAmount(new BigDecimal("6"));
    return item;
  }

  private CostRunCostItemDto totalCost(String amount) {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode("TOTAL");
    item.setCostName("不含税总成本");
    item.setAmount(new BigDecimal(amount));
    return item;
  }

  private static final class FakeResultService implements CostRunResultService {
    private final List<BigDecimal> totalCostUpdates = new ArrayList<>();
    private final List<OaFormItem> savedItems = new ArrayList<>();

    @Override
    public com.sanhua.marketingcost.dto.CostRunResultDto getResult(String oaNo, String productCode) {
      return null;
    }

    @Override
    public void saveOrUpdate(OaForm form, OaFormItem item) {
      savedItems.add(item);
    }

    @Override
    public void updateTotalCost(String oaNo, String productCode, BigDecimal totalCost) {
      totalCostUpdates.add(totalCost);
    }
  }

  private static final class FakeTraceSnapshotService implements CostRunTraceSnapshotService {
    private final List<QuoteCostRunVersion> rebuilt = new ArrayList<>();
    private boolean fail;

    @Override
    public int rebuildForVersion(QuoteCostRunVersion version) {
      rebuilt.add(version);
      if (fail) {
        throw new IllegalStateException("trace rebuild failed");
      }
      return 0;
    }
  }

  private static final class PartMapperState {
    private final List<CostRunPartItem> rows = new ArrayList<>();

    CostRunPartItemMapper proxy() {
      InvocationHandler handler =
          (proxy, method, args) -> {
            if ("deleteQuoteItems".equals(method.getName())) {
              String oaNo = (String) args[0];
              String productCode = (String) args[1];
              rows.removeIf(row -> oaNo.equals(row.getOaNo()) && productCode.equals(row.getProductCode()));
              return 1;
            }
            if ("deleteQuoteItemsByCostRunNo".equals(method.getName())) {
              String costRunNo = (String) args[0];
              rows.removeIf(row -> costRunNo.equals(row.getCostRunNo()));
              return 1;
            }
            if ("insert".equals(method.getName())) {
              rows.add((CostRunPartItem) args[0]);
              return 1;
            }
            throw new UnsupportedOperationException(method.getName());
          };
      return (CostRunPartItemMapper)
          Proxy.newProxyInstance(
              CostRunPartItemMapper.class.getClassLoader(),
              new Class<?>[] {CostRunPartItemMapper.class},
              handler);
    }
  }

  private static final class CostMapperState {
    private final List<CostRunCostItem> rows = new ArrayList<>();

    CostRunCostItemMapper proxy() {
      InvocationHandler handler =
          (proxy, method, args) -> {
            if ("deleteQuoteItems".equals(method.getName())) {
              String oaNo = (String) args[0];
              String productCode = (String) args[1];
              rows.removeIf(row -> oaNo.equals(row.getOaNo()) && productCode.equals(row.getProductCode()));
              return 1;
            }
            if ("deleteQuoteItemsByCostRunNo".equals(method.getName())) {
              String costRunNo = (String) args[0];
              rows.removeIf(row -> costRunNo.equals(row.getCostRunNo()));
              return 1;
            }
            if ("insert".equals(method.getName())) {
              rows.add((CostRunCostItem) args[0]);
              return 1;
            }
            throw new UnsupportedOperationException(method.getName());
          };
      return (CostRunCostItemMapper)
          Proxy.newProxyInstance(
              CostRunCostItemMapper.class.getClassLoader(),
              new Class<?>[] {CostRunCostItemMapper.class},
              handler);
    }
  }

  private static final class ResultMapperState {
    private final List<CostRunResult> rows = new ArrayList<>();

    CostRunResultMapper proxy() {
      InvocationHandler handler =
          (proxy, method, args) -> {
            if ("selectOne".equals(method.getName())) {
              return null;
            }
            if ("insert".equals(method.getName())) {
              rows.add((CostRunResult) args[0]);
              return 1;
            }
            if ("updateById".equals(method.getName())) {
              return 1;
            }
            throw new UnsupportedOperationException(method.getName());
          };
      return (CostRunResultMapper)
          Proxy.newProxyInstance(
              CostRunResultMapper.class.getClassLoader(),
              new Class<?>[] {CostRunResultMapper.class},
              handler);
    }
  }

  private static PricePrepareItemMapper pricePrepareItemMapper() {
    InvocationHandler handler =
        (proxy, method, args) -> {
          if ("selectOne".equals(method.getName())) {
            PricePrepareItem item = new PricePrepareItem();
            item.setId(9501L);
            return item;
          }
          throw new UnsupportedOperationException(method.getName());
        };
    return (PricePrepareItemMapper)
        Proxy.newProxyInstance(
            PricePrepareItemMapper.class.getClassLoader(),
            new Class<?>[] {PricePrepareItemMapper.class},
            handler);
  }
}
