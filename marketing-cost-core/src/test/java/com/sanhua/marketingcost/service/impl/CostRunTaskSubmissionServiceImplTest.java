package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.CostRunMonthlyRepriceSubmitRequest;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.entity.CostRunBatch;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkspaceMapper;
import com.sanhua.marketingcost.service.CostingAlgorithmVersionProvider;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.YearMonth;
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
            batchMapper.proxy(), taskMapper.proxy(), oaFormMapper.proxy(), oaFormItemMapper.proxy(),
            emptyWorkspaceMapper(), emptyVersionMapper(), algorithmVersion());

    CostRunTaskSubmissionResult result = service.submitQuote(" OA-001 ");

    assertThat(result.getScene()).isEqualTo("QUOTE");
    assertThat(result.getSourceNo()).isEqualTo("OA-001");
    assertThat(result.getTotalCount()).isEqualTo(2);
    assertThat(result.getTaskCount()).isEqualTo(2);
    assertThat(result.isExistingBatch()).isFalse();
    assertThat(batchMapper.inserted).hasSize(1);
    assertThat(batchMapper.inserted.get(0).getPricingMonth()).isEqualTo(YearMonth.now().toString());
    assertThat(batchMapper.inserted.get(0).getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(batchMapper.inserted.get(0).getExecutionNo()).isEqualTo(1);
    assertThat(batchMapper.inserted.get(0).getPrerequisiteStatus()).isEqualTo("PENDING");
    assertThat(batchMapper.inserted.get(0).getControlVersion()).isZero();
    assertThat(taskMapper.inserted)
        .extracting(CostRunTask::getCalcObjectKey)
        .containsExactly("QUOTE:11", "QUOTE:12");
    assertThat(taskMapper.inserted).extracting(CostRunTask::getExecutionNo).containsOnly(1);
    assertThat(taskMapper.inserted)
        .extracting(CostRunTask::getPricingMonth)
        .containsOnly(YearMonth.now().toString());
  }

  @Test
  void submitQuoteUsesCurrentMonthInsteadOfOaHistoricalPeriods() {
    FakeBatchMapper batchMapper = new FakeBatchMapper(null);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    FakeOaFormMapper oaFormMapper = new FakeOaFormMapper(oaFormWithHistoricalPeriods());
    FakeOaFormItemMapper oaFormItemMapper =
        new FakeOaFormItemMapper(List.of(item(11L, "P-001", "BOX")));
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(), taskMapper.proxy(), oaFormMapper.proxy(), oaFormItemMapper.proxy(),
            emptyWorkspaceMapper(), emptyVersionMapper(), algorithmVersion());

    service.submitQuote("OA-001");

    assertThat(batchMapper.inserted).hasSize(1);
    assertThat(batchMapper.inserted.get(0).getPricingMonth()).isEqualTo(YearMonth.now().toString());
    assertThat(taskMapper.inserted).hasSize(1);
    assertThat(taskMapper.inserted.get(0).getPricingMonth()).isEqualTo(YearMonth.now().toString());
  }

  @Test
  void submitQuoteRerunsExistingBatchTasks() {
    CostRunBatch existing = existingBatch("BATCH-EXISTING", "QUOTE", "OA-001");
    existing.setStatus("SUCCESS");
    FakeBatchMapper batchMapper = new FakeBatchMapper(existing);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    FakeOaFormMapper oaFormMapper = new FakeOaFormMapper(oaForm());
    FakeOaFormItemMapper oaFormItemMapper =
        new FakeOaFormItemMapper(List.of(item(11L, "P-001", "BOX")));
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(), taskMapper.proxy(), oaFormMapper.proxy(), oaFormItemMapper.proxy(),
            emptyWorkspaceMapper(), emptyVersionMapper(), algorithmVersion());

    CostRunTaskSubmissionResult result = service.submitQuote("OA-001");

    assertThat(result.getBatchNo()).isEqualTo("BATCH-EXISTING");
    assertThat(result.isExistingBatch()).isTrue();
    assertThat(batchMapper.inserted).isEmpty();
    assertThat(batchMapper.resetBatchNos).isEmpty();
    assertThat(batchMapper.quoteRerunBatchNos).containsExactly("BATCH-EXISTING");
    assertThat(batchMapper.quoteRerunExpectedExecutionNos).containsExactly(1);
    assertThat(batchMapper.quoteRerunExpectedControlVersions).containsExactly(2);
    assertThat(batchMapper.quoteRerunTotalCounts).containsExactly(1);
    assertThat(batchMapper.quoteRerunSkippedCounts).containsExactly(0);
    assertThat(taskMapper.resetBatchNos).isEmpty();
    assertThat(taskMapper.quoteRerunBatchNos).containsExactly("BATCH-EXISTING");
    assertThat(taskMapper.quoteRerunKeys).containsExactly(List.of("QUOTE:11"));
    assertThat(taskMapper.quoteRerunExecutionNos).containsExactly(2);
    assertThat(taskMapper.inserted).hasSize(1);
    assertThat(taskMapper.inserted.get(0).getExecutionNo()).isEqualTo(2);
  }

  @Test
  void submitQuoteRerunsOnlySelectedTasksForFailedExistingBatch() {
    CostRunBatch existing = existingBatch("BATCH-FAILED", "QUOTE", "OA-001");
    existing.setStatus("FAILED");
    FakeBatchMapper batchMapper = new FakeBatchMapper(existing);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    FakeOaFormMapper oaFormMapper = new FakeOaFormMapper(oaForm());
    FakeOaFormItemMapper oaFormItemMapper =
        new FakeOaFormItemMapper(List.of(item(11L, "P-001", "BOX"), item(12L, "P-002", "BAG")));
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(), taskMapper.proxy(), oaFormMapper.proxy(), oaFormItemMapper.proxy(),
            emptyWorkspaceMapper(), emptyVersionMapper(), algorithmVersion());

    CostRunTaskSubmissionResult result =
        service.submitQuote(
            "OA-001", List.of(11L), YearMonth.now().toString(), "quote-user");

    assertThat(result.getBatchNo()).isEqualTo("BATCH-FAILED");
    assertThat(result.isExistingBatch()).isTrue();
    assertThat(result.getTotalCount()).isEqualTo(1);
    assertThat(batchMapper.inserted).isEmpty();
    assertThat(batchMapper.resetBatchNos).isEmpty();
    assertThat(batchMapper.quoteRerunBatchNos).containsExactly("BATCH-FAILED");
    assertThat(batchMapper.quoteRerunTotalCounts).containsExactly(1);
    assertThat(batchMapper.quoteRerunSkippedCounts).containsExactly(0);
    assertThat(taskMapper.resetBatchNos).isEmpty();
    assertThat(taskMapper.quoteRerunBatchNos).containsExactly("BATCH-FAILED");
    assertThat(taskMapper.quoteRerunKeys).containsExactly(List.of("QUOTE:11"));
    assertThat(taskMapper.quoteRerunSnapshots)
        .singleElement()
        .asString()
        .contains("\"submittedBy\":\"quote-user\"");
    assertThat(taskMapper.inserted)
        .extracting(CostRunTask::getCalcObjectKey)
        .containsExactly("QUOTE:11");
  }

  @Test
  void submitQuoteReusesActiveBatchWithoutResettingTasks() {
    CostRunBatch existing = existingBatch("BATCH-RUNNING", "QUOTE", "OA-001");
    FakeBatchMapper batchMapper = new FakeBatchMapper(existing);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(),
            taskMapper.proxy(),
            new FakeOaFormMapper(oaForm()).proxy(),
            new FakeOaFormItemMapper(List.of(item(11L, "P-001", "BOX"))).proxy(),
            emptyWorkspaceMapper(),
            emptyVersionMapper(),
            algorithmVersion());

    CostRunTaskSubmissionResult result = service.submitQuote("OA-001");

    assertThat(result.getBatchNo()).isEqualTo("BATCH-RUNNING");
    assertThat(result.isExistingBatch()).isTrue();
    assertThat(taskMapper.quoteRerunBatchNos).isEmpty();
    assertThat(taskMapper.inserted).hasSize(1);
    assertThat(batchMapper.syncedBatchNos).containsExactly("BATCH-RUNNING");
  }

  @Test
  void submitQuoteReusesActiveBatchWhenWorkspaceHasNoCurrentCostVersion() {
    CostRunBatch existing = existingBatch("BATCH-RUNNING", "QUOTE", "OA-001");
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setOaFormItemId(11L);
    workspace.setPeriodMonth(YearMonth.now().toString());
    workspace.setWorkspaceStatus("BLOCKED");
    workspace.setCurrentCostVersionId(null);
    FakeBatchMapper batchMapper = new FakeBatchMapper(existing);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(),
            taskMapper.proxy(),
            new FakeOaFormMapper(oaForm()).proxy(),
            new FakeOaFormItemMapper(List.of(item(11L, "P-001", "BOX"))).proxy(),
            workspaceMapper(List.of(workspace)),
            emptyVersionMapper(),
            algorithmVersion());

    CostRunTaskSubmissionResult result = service.submitQuote("OA-001");

    assertThat(result.getBatchNo()).isEqualTo("BATCH-RUNNING");
    assertThat(result.isExistingBatch()).isTrue();
    assertThat(taskMapper.quoteRerunBatchNos).isEmpty();
    assertThat(taskMapper.inserted).hasSize(1);
  }

  @Test
  void submitQuoteSkipsCurrentSuccessWithUnchangedInput() {
    OaFormItem currentItem = item(11L, "P-001", "BOX");
    currentItem.setConfirmedCostVersionId(99L);
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setOaFormItemId(11L);
    workspace.setPeriodMonth(YearMonth.now().toString());
    workspace.setWorkspaceStatus("SUCCESS");
    workspace.setCurrentCostVersionId(99L);
    workspace.setInputFingerprint("FP-1");
    workspace.setLastSuccessInputFingerprint("FP-1");
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(99L);
    version.setOaNo("OA-001");
    version.setOaFormItemId(11L);
    version.setPricingMonth(YearMonth.now().toString());
    version.setInputFingerprint("FP-1");
    version.setAlgorithmVersion(CostingAlgorithmVersionProvider.DEFAULT_VERSION);
    version.setStatus("SUCCESS");
    FakeBatchMapper batchMapper = new FakeBatchMapper(null);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(),
            taskMapper.proxy(),
            new FakeOaFormMapper(oaForm()).proxy(),
            new FakeOaFormItemMapper(List.of(currentItem)).proxy(),
            workspaceMapper(List.of(workspace)),
            versionMapper(List.of(version)),
            algorithmVersion());

    CostRunTaskSubmissionResult result = service.submitQuote("OA-001");

    assertThat(result.getTotalCount()).isEqualTo(1);
    assertThat(result.getQueuedCount()).isZero();
    assertThat(result.getSkippedCount()).isEqualTo(1);
    assertThat(batchMapper.inserted.get(0).getPrerequisiteStatus()).isEqualTo("NOT_REQUIRED");
    assertThat(taskMapper.inserted).singleElement().satisfies(task -> {
      assertThat(task.getStatus()).isEqualTo("SKIPPED_CURRENT");
      assertThat(task.getProgress()).isEqualTo(100);
    });
  }

  @Test
  void submitQuoteQueuesCurrentSuccessWhenAlgorithmVersionChanged() {
    OaFormItem currentItem = item(11L, "P-001", "BOX");
    currentItem.setConfirmedCostVersionId(99L);
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setOaFormItemId(11L);
    workspace.setPeriodMonth(YearMonth.now().toString());
    workspace.setWorkspaceStatus("SUCCESS");
    workspace.setCurrentCostVersionId(99L);
    workspace.setInputFingerprint("FP-1");
    workspace.setLastSuccessInputFingerprint("FP-1");
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(99L);
    version.setOaNo("OA-001");
    version.setOaFormItemId(11L);
    version.setPricingMonth(YearMonth.now().toString());
    version.setInputFingerprint("FP-1");
    version.setAlgorithmVersion("LEGACY");
    version.setStatus("SUCCESS");
    FakeBatchMapper batchMapper = new FakeBatchMapper(null);
    FakeTaskMapper taskMapper = new FakeTaskMapper();
    CostRunTaskSubmissionServiceImpl service =
        new CostRunTaskSubmissionServiceImpl(
            batchMapper.proxy(),
            taskMapper.proxy(),
            new FakeOaFormMapper(oaForm()).proxy(),
            new FakeOaFormItemMapper(List.of(currentItem)).proxy(),
            workspaceMapper(List.of(workspace)),
            versionMapper(List.of(version)),
            algorithmVersion());

    CostRunTaskSubmissionResult result = service.submitQuote("OA-001");

    assertThat(result.getQueuedCount()).isEqualTo(1);
    assertThat(result.getSkippedCount()).isZero();
    assertThat(batchMapper.inserted.get(0).getPrerequisiteStatus()).isEqualTo("PENDING");
    assertThat(taskMapper.inserted).singleElement().satisfies(task -> {
      assertThat(task.getStatus()).isEqualTo("PENDING");
      assertThat(task.getProgress()).isZero();
    });
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
            new FakeOaFormItemMapper(List.of()).proxy(),
            emptyWorkspaceMapper(),
            emptyVersionMapper(),
            algorithmVersion());
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

  private OaForm oaFormWithHistoricalPeriods() {
    OaForm form = oaForm();
    form.setAccountingPeriodMonth("2026-01");
    form.setApplyDate(LocalDate.of(2026, 1, 8));
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
    batch.setExecutionNo(1);
    batch.setPrerequisiteStatus("SUCCESS");
    batch.setControlVersion(2);
    batch.setPricingMonth(YearMonth.now().toString());
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

  private QuoteCostingWorkspaceMapper emptyWorkspaceMapper() {
    return workspaceMapper(List.of());
  }

  private QuoteCostingWorkspaceMapper workspaceMapper(List<QuoteCostingWorkspace> rows) {
    return (QuoteCostingWorkspaceMapper)
        Proxy.newProxyInstance(
            QuoteCostingWorkspaceMapper.class.getClassLoader(),
            new Class<?>[] {QuoteCostingWorkspaceMapper.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "selectByItemsAndMonth" -> rows;
                  case "toString" -> "EmptyQuoteCostingWorkspaceMapper";
                  default -> throw new UnsupportedOperationException(method.toString());
                });
  }

  private QuoteCostRunVersionMapper emptyVersionMapper() {
    return versionMapper(List.of());
  }

  private QuoteCostRunVersionMapper versionMapper(List<QuoteCostRunVersion> rows) {
    return (QuoteCostRunVersionMapper)
        Proxy.newProxyInstance(
            QuoteCostRunVersionMapper.class.getClassLoader(),
            new Class<?>[] {QuoteCostRunVersionMapper.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "selectBatchIds" -> rows;
                  case "toString" -> "EmptyQuoteCostRunVersionMapper";
                  default -> throw new UnsupportedOperationException(method.toString());
                });
  }

  private CostingAlgorithmVersionProvider algorithmVersion() {
    return new CostingAlgorithmVersionProvider(CostingAlgorithmVersionProvider.DEFAULT_VERSION);
  }

  private static class FakeBatchMapper {
    private final CostRunBatch existing;
    private final List<CostRunBatch> inserted = new ArrayList<>();
    private final List<String> resetBatchNos = new ArrayList<>();
    private final List<String> quoteRerunBatchNos = new ArrayList<>();
    private final List<Integer> quoteRerunExpectedExecutionNos = new ArrayList<>();
    private final List<Integer> quoteRerunExpectedControlVersions = new ArrayList<>();
    private final List<Integer> quoteRerunTotalCounts = new ArrayList<>();
    private final List<Integer> quoteRerunSkippedCounts = new ArrayList<>();
    private final List<String> syncedBatchNos = new ArrayList<>();
    private int quoteResetResult = 1;

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
                    case "resetFailedBatchForRetry" -> {
                      resetBatchNos.add((String) args[0]);
                      yield 1;
                    }
                    case "resetQuoteBatchForRerun" -> {
                      quoteRerunBatchNos.add((String) args[0]);
                      quoteRerunExpectedExecutionNos.add((Integer) args[1]);
                      quoteRerunExpectedControlVersions.add((Integer) args[2]);
                      quoteRerunTotalCounts.add((Integer) args[3]);
                      quoteRerunSkippedCounts.add((Integer) args[4]);
                      yield quoteResetResult;
                    }
                    case "syncActiveQuoteBatchCounts" -> {
                      syncedBatchNos.add((String) args[0]);
                      yield 1;
                    }
                    case "toString" -> "FakeCostRunBatchMapper";
                    default -> throw new UnsupportedOperationException(method.toString());
                  });
    }
  }

  private static class FakeTaskMapper {
    private final List<CostRunTask> inserted = new ArrayList<>();
    private final List<String> resetBatchNos = new ArrayList<>();
    private final List<String> quoteRerunBatchNos = new ArrayList<>();
    private final List<List<String>> quoteRerunKeys = new ArrayList<>();
    private final List<Integer> quoteRerunExecutionNos = new ArrayList<>();
    private final List<String> quoteRerunSnapshots = new ArrayList<>();

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
                    case "resetBatchTasksForRetry" -> {
                      resetBatchNos.add((String) args[0]);
                      yield 1;
                    }
                    case "resetQuoteTasksForRerun" -> {
                      quoteRerunBatchNos.add((String) args[0]);
                      quoteRerunKeys.add((List<String>) args[1]);
                      quoteRerunExecutionNos.add((Integer) args[2]);
                      quoteRerunSnapshots.add((String) args[4]);
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
