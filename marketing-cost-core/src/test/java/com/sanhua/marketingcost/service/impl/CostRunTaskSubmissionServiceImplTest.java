package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.CostRunMonthlyRepriceSubmitRequest;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.entity.CostRunBatch;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostRunTaskSubmissionServiceImplTest {

  @Test
  void submitQuoteCreatesBatchAndProductTasks() {
    FakeBatchMapper batchMapper = new FakeBatchMapper(null);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    FakeOaFormMapper oaFormMapper = new FakeOaFormMapper(oaForm());
    FakeOaFormItemMapper oaFormItemMapper =
        new FakeOaFormItemMapper(List.of(item(11L, "P-001", "BOX"), item(12L, "P-002", "BAG")));
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(), taskMapper.proxy(), oaFormMapper.proxy(), oaFormItemMapper.proxy());

    CostRunTaskSubmissionResult result = service.submitQuote(" OA-001 ");

    assertThat(result.getScene()).isEqualTo("QUOTE");
    assertThat(result.getSourceNo()).isEqualTo("OA-001");
    assertThat(result.getTotalCount()).isEqualTo(2);
    assertThat(result.getTaskCount()).isEqualTo(2);
    assertThat(result.isExistingBatch()).isFalse();
    assertThat(batchMapper.inserted).hasSize(1);
    assertThat(batchMapper.inserted.get(0).getPricingMonth()).isEqualTo("2026-05");
    assertThat(batchMapper.inserted.get(0).getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(taskMapper.inserted)
        .extracting(CostRunTask::getCalcObjectKey)
        .containsExactly("QUOTE:11", "QUOTE:12");
  }

  @Test
  void submitQuoteReturnsExistingBatchWithoutCreatingAnotherBatch() {
    CostRunBatch existing = existingBatch("BATCH-EXISTING", "QUOTE", "OA-001");
    FakeBatchMapper batchMapper = new FakeBatchMapper(existing);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    FakeOaFormMapper oaFormMapper = new FakeOaFormMapper(oaForm());
    FakeOaFormItemMapper oaFormItemMapper =
        new FakeOaFormItemMapper(List.of(item(11L, "P-001", "BOX")));
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(), taskMapper.proxy(), oaFormMapper.proxy(), oaFormItemMapper.proxy());

    CostRunTaskSubmissionResult result = service.submitQuote("OA-001");

    assertThat(result.getBatchNo()).isEqualTo("BATCH-EXISTING");
    assertThat(result.isExistingBatch()).isTrue();
    assertThat(batchMapper.inserted).isEmpty();
    assertThat(taskMapper.inserted).hasSize(1);
  }

  @Test
  void submitMonthlyRepriceDeduplicatesCalcObjectsByCanonicalKey() {
    FakeBatchMapper batchMapper = new FakeBatchMapper(null);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(),
            taskMapper.proxy(),
            new FakeOaFormMapper(null).proxy(),
            new FakeOaFormItemMapper(List.of()).proxy());
    CostRunMonthlyRepriceSubmitRequest request = new CostRunMonthlyRepriceSubmitRequest();
    request.setRepriceNo("MRP-001");
    request.setPricingMonth("2026-05");
    request.setBusinessUnitType("COMMERCIAL");
    request.setAdjustBatchId(100L);
    request.setBomSourcePolicy("OA_CALCULATED");
    request.setCalcObjects(
        List.of(
            monthlyObject("OA-1", 11L, " P-001 ", "Box", "Acme  Inc"),
            monthlyObject("OA-1", 12L, "P-001", " Box ", "ACME INC")));

    CostRunTaskSubmissionResult result = service.submitMonthlyReprice(request);

    assertThat(result.getScene()).isEqualTo("MONTHLY_REPRICE");
    assertThat(result.getTaskCount()).isEqualTo(1);
    assertThat(result.getSkippedCount()).isEqualTo(1);
    assertThat(taskMapper.inserted).hasSize(1);
    CostRunTask task = taskMapper.inserted.get(0);
    assertThat(task.getAdjustBatchId()).isEqualTo(100L);
    assertThat(task.getBomSourcePolicy()).isEqualTo("OA_CALCULATED");
    assertThat(task.getCalcObjectKey()).hasSize(64);
  }

  private OaForm oaForm() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-001");
    form.setCustomer("Acme");
    form.setBusinessUnitType("COMMERCIAL");
    form.setApplyDate(LocalDate.of(2026, 5, 1));
    return form;
  }

  private OaFormItem item(Long id, String materialNo, String packageMethod) {
    OaFormItem item = new OaFormItem();
    item.setId(id);
    item.setOaFormId(1L);
    item.setMaterialNo(materialNo);
    item.setPackageMethod(packageMethod);
    return item;
  }

  private CostRunBatch existingBatch(String batchNo, String scene, String sourceNo) {
    CostRunBatch batch = new CostRunBatch();
    batch.setBatchNo(batchNo);
    batch.setScene(scene);
    batch.setSourceNo(sourceNo);
    batch.setStatus("PENDING");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    return batch;
  }

  private MonthlyRepriceCalcObject monthlyObject(
      String oaNo, Long itemId, String productCode, String packageMethod, String customerName) {
    MonthlyRepriceCalcObject object = new MonthlyRepriceCalcObject();
    object.setOaNo(oaNo);
    object.setOaFormItemId(itemId);
    object.setProductCode(productCode);
    object.setPackageMethod(packageMethod);
    object.setCustomerName(customerName);
    return object;
  }

  private static class FakeBatchMapper {
    private final CostRunBatch existing;
    private final List<CostRunBatch> inserted = new ArrayList<>();

    FakeBatchMapper(CostRunBatch existing) {
      this.existing = existing;
    }

    CostRunBatchMapper proxy() {
      return (CostRunBatchMapper)
          Proxy.newProxyInstance(
              CostRunBatchMapper.class.getClassLoader(),
              new Class<?>[] {CostRunBatchMapper.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "selectOne" -> existing;
                    case "insertIgnore" -> {
                      inserted.add((CostRunBatch) args[0]);
                      yield 1;
                    }
                    case "toString" -> "FakeCostRunBatchMapper";
                    default -> throw new UnsupportedOperationException(method.toString());
                  });
    }
  }

  private static class FakeTaskMapper {
    private final List<CostRunTask> inserted = new ArrayList<>();

    CostRunTaskMapper proxy() {
      return (CostRunTaskMapper)
          Proxy.newProxyInstance(
              CostRunTaskMapper.class.getClassLoader(),
              new Class<?>[] {CostRunTaskMapper.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "insertIgnore" -> {
                      inserted.add((CostRunTask) args[0]);
                      yield 1;
                    }
                    case "toString" -> "FakeCostRunTaskMapper";
                    default -> throw new UnsupportedOperationException(method.toString());
                  });
    }
  }

  private static class FakeOaFormMapper {
    private final OaForm form;

    FakeOaFormMapper(OaForm form) {
      this.form = form;
    }

    OaFormMapper proxy() {
      return (OaFormMapper)
          Proxy.newProxyInstance(
              OaFormMapper.class.getClassLoader(),
              new Class<?>[] {OaFormMapper.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "selectOne" -> form;
                    case "toString" -> "FakeOaFormMapper";
                    default -> throw new UnsupportedOperationException(method.toString());
                  });
    }
  }

  private static class FakeOaFormItemMapper {
    private final List<OaFormItem> items;

    FakeOaFormItemMapper(List<OaFormItem> items) {
      this.items = items;
    }

    OaFormItemMapper proxy() {
      return (OaFormItemMapper)
          Proxy.newProxyInstance(
              OaFormItemMapper.class.getClassLoader(),
              new Class<?>[] {OaFormItemMapper.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "selectList" -> items;
                    case "toString" -> "FakeOaFormItemMapper";
                    default -> throw new UnsupportedOperationException(method.toString());
                  });
    }
  }
}
