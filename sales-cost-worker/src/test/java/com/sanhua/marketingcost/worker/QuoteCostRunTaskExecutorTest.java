package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuoteCostRunTaskExecutorTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
    TableInfoHelper.initTableInfo(assistant, OaFormItem.class);
  }

  @Test
  @DisplayName("T32：QUOTE worker 按旧 API 链路顺序执行并写入普通报价结果")
  void quoteWorkerRunsFullQuoteChainInApiSyncOrder() {
    Harness harness = new Harness();

    CostRunTaskExecutionResult executionResult =
        harness.executor.execute(quoteTask(), "worker-1");

    assertThat(executionResult.resultSummaryJson())
        .isEqualTo("{\"partItemCount\":1,\"costItemCount\":0,\"totalCost\":\"123.45\"}");
    assertThat(harness.engineContext.getScene()).isEqualTo(CostRunContext.SCENE_QUOTE);
    assertThat(harness.engineContext.getOaNo()).isEqualTo("OA-1");
    assertThat(harness.engineContext.getOaFormItemId()).isEqualTo(11L);
    assertThat(harness.engineContext.getProductCode()).isEqualTo("PROD-1");
    assertThat(harness.engineContext.getPackageMethod()).isEqualTo("BOX");
    assertThat(harness.engineContext.getCustomerName()).isEqualTo("ACME");
    assertThat(harness.engineContext.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(harness.engineContext.getPricingMonth()).isEqualTo("2026-05");
    assertThat(harness.engineContext.getCostRunVersionId()).isEqualTo(9011L);
    assertThat(harness.engineContext.getCostRunNo()).isEqualTo("TRIAL-11");
    assertThat(harness.engineContext.getPricePrepareNo()).isEqualTo("PPR-1");
    assertThat(harness.ensureRequest.getItemCodes()).containsExactly("PART-LINK");
    assertThat(harness.writtenResult).isSameAs(harness.engineResult);
    assertThat(harness.writtenForm).isSameAs(harness.form);
    assertThat(harness.writtenItem).isSameAs(harness.item);
    assertThat(harness.progressValues).containsExactly(5, 10, 52, 95);
    assertThat(harness.calls)
        .containsExactly("sync", "readiness", "ensure", "engine", "writer", "itemUpdate", "oaUpdate");
  }

  @Test
  @DisplayName("T32：价格准备阻断时异常信息保留给 worker 回写 task")
  void blockingPriceReadinessThrowsMessageForTaskFailure() {
    Harness harness = new Harness();
    harness.readiness =
        PricePrepareReadinessResult.notReady(
            "BLOCKING", false, true, "缺少采购价", "PPR-1", "2026-05", "FAILED", 1, List.of("PART-1"));

    assertThatThrownBy(() -> harness.executor.execute(quoteTask(), "worker-1"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("缺少采购价");
    assertThat(harness.calls).containsExactly("sync", "readiness");
  }

  @Test
  @DisplayName("PPR-09：价格准备未完成但允许继续时 worker 不阻断")
  void nonBlockingPriceReadinessWarningContinuesWorkerTask() {
    Harness harness = new Harness();
    harness.readiness =
        PricePrepareReadinessResult.notReady(
            "PARTIAL",
            true,
            false,
            "价格准备未完成，实时成本将继续，结果可能缺价",
            "PPR-1",
            "2026-05",
            "PARTIAL",
            1,
            List.of("PART-1: 缺价"));

    CostRunTaskExecutionResult executionResult =
        harness.executor.execute(quoteTask(), "worker-1");

    assertThat(executionResult.resultSummaryJson())
        .isEqualTo("{\"partItemCount\":1,\"costItemCount\":0,\"totalCost\":\"123.45\"}");
    assertThat(harness.calls)
        .containsExactly("sync", "readiness", "ensure", "engine", "writer", "itemUpdate", "oaUpdate");
  }

  @Test
  @DisplayName("LPE-08：QUOTE worker ensure 返回失败项时提示并继续部品取价")
  void quoteWorkerContinuesWhenEnsureReturnsFailedItems() {
    Harness harness = new Harness();
    harness.ensureResult.addFailedItem("PART-LINK", "联动价公式不存在或为空");

    CostRunTaskExecutionResult executionResult =
        harness.executor.execute(quoteTask(), "worker-1");

    assertThat(executionResult.resultSummaryJson())
        .isEqualTo("{\"partItemCount\":1,\"costItemCount\":0,\"totalCost\":\"123.45\"}");
    assertThat(harness.calls)
        .containsExactly("sync", "readiness", "ensure", "engine", "writer", "itemUpdate", "oaUpdate");
    assertThat(harness.progressValues).containsExactly(5, 10, 52, 95);
    assertThat(harness.writtenResult).isSameAs(harness.engineResult);
  }

  @Test
  @DisplayName("T32：QUOTE worker 未带核算月时按当前月执行")
  void quoteWorkerUsesCurrentPeriodWhenTaskHasNoPricingMonth() {
    Harness harness = new Harness();
    CostRunTask task = quoteTask();
    task.setPricingMonth(null);

    harness.executor.execute(task, "worker-1");

    assertThat(harness.readinessPeriod).isEqualTo(CostPricingPeriodUtils.currentPricingMonth());
    assertThat(harness.engineContext.getPricingMonth())
        .isEqualTo(CostPricingPeriodUtils.currentPricingMonth());
    assertThat(harness.ensureRequest.getPricingMonth())
        .isEqualTo(CostPricingPeriodUtils.currentPricingMonth());
  }

  private static class Harness {
    private final List<String> calls = new ArrayList<>();
    private final List<Integer> progressValues = new ArrayList<>();
    private final OaForm form = form();
    private final OaFormItem item = item();
    private final CostRunObjectResult engineResult = result();
    private final QuoteCostRunTaskExecutor executor;
    private PricePrepareReadinessResult readiness =
        PricePrepareReadinessResult.ready("PPR-1", "2026-05", "SUCCESS");
    private String readinessPeriod;
    private CostRunContext engineContext;
    private LinkedPriceEnsureRequest ensureRequest;
    private LinkedPriceEnsureResult ensureResult = new LinkedPriceEnsureResult();
    private CostRunObjectResult writtenResult;
    private OaForm writtenForm;
    private OaFormItem writtenItem;

    private Harness() {
      OaFormMapper oaFormMapper =
          mapperProxy(
              OaFormMapper.class,
              (proxy, method, args) -> {
                if ("selectOne".equals(method.getName())) {
                  return form;
                }
                if ("update".equals(method.getName())) {
                  calls.add("oaUpdate");
                  return 1;
                }
                return defaultValue(method.getReturnType());
              });
      OaFormItemMapper oaFormItemMapper =
          mapperProxy(
              OaFormItemMapper.class,
              (proxy, method, args) -> {
                if ("selectById".equals(method.getName())) {
                  return item;
                }
                if ("markCalculated".equals(method.getName())) {
                  calls.add("itemUpdate");
                  return 1;
                }
                if ("countRunnableItems".equals(method.getName())) {
                  return 1L;
                }
                if ("countCalculatedRunnableItems".equals(method.getName())) {
                  return 1L;
                }
                return defaultValue(method.getReturnType());
              });
      CostRunPartItemMapper costRunPartItemMapper =
          mapperProxy(
              CostRunPartItemMapper.class,
              (proxy, method, args) -> "selectBaseByOaNo".equals(method.getName())
                  ? List.of(part("PART-LINK"))
                  : defaultValue(method.getReturnType()));
      CostRunTaskMapper costRunTaskMapper =
          mapperProxy(
              CostRunTaskMapper.class,
              (proxy, method, args) -> {
                if ("updateProgress".equals(method.getName())) {
                  progressValues.add((Integer) args[2]);
                  return 1;
                }
                return defaultValue(method.getReturnType());
              });
      MaterialMasterSyncService materialMasterSyncService =
          new MaterialMasterSyncService() {
            @Override
            public SyncResult syncByOaNo(String oaNo) {
              calls.add("sync");
              return new SyncResult(2, 2, 2, "MM-1");
            }
          };
      PricePrepareReadinessService pricePrepareReadinessService =
          new PricePrepareReadinessService() {
            @Override
            public PricePrepareReadinessResult check(String oaNo, String periodMonth) {
              calls.add("readiness");
              readinessPeriod = periodMonth;
              return readiness;
            }

            @Override
            public PricePrepareReadinessResult check(
                String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
              calls.add("readiness");
              readinessPeriod = periodMonth;
              return readiness;
            }

            @Override
            public PricePrepareReadinessResult check(
                String oaNo,
                Long oaFormItemId,
                String topProductCode,
                String periodMonth,
                String priceTypeConfirmNo) {
              calls.add("readiness");
              readinessPeriod = periodMonth;
              return readiness;
            }
          };
      MaterialPriceRouterService materialPriceRouterService =
          new MaterialPriceRouterService() {
            @Override
            public java.util.Optional<PriceTypeRoute> resolve(
                String materialCode, String period, LocalDate quoteDate) {
              return java.util.Optional.empty();
            }

            @Override
            public List<PriceTypeRoute> listCandidates(
                String materialCode, String period, LocalDate quoteDate) {
              return List.of(
                  new PriceTypeRoute(
                      materialCode, null, PriceTypeEnum.LINKED, 1, null, null, "CMS", "联动价"));
            }
          };
      LinkedPriceEnsureService linkedPriceEnsureService =
          request -> {
            calls.add("ensure");
            ensureRequest = request;
            return ensureResult;
          };
      CostRunEngine costRunEngine =
          context -> {
            calls.add("engine");
            engineContext = context;
            context.getProgress().accept(50);
            engineResult.setContext(context);
            return engineResult;
          };
      CostRunResultWriter costRunResultWriter =
          (result, form, item) -> {
            calls.add("writer");
            writtenResult = result;
            writtenForm = form;
            writtenItem = item;
          };
      QuoteCostRunVersionService quoteCostRunVersionService =
          new QuoteCostRunVersionService() {
            @Override
            public QuoteCostRunVersion createTrial(
                String oaNo,
                Long oaFormItemId,
                String productCode,
                String pricingMonth,
                String resultPeriod,
                String pricePrepareNo,
                String priceTypeConfirmNo,
                String bomConfirmNo,
                String businessUnitType) {
              QuoteCostRunVersion version = new QuoteCostRunVersion();
              version.setId(9000L + oaFormItemId);
              version.setCostRunNo("TRIAL-" + oaFormItemId);
              version.setPricePrepareNo(pricePrepareNo);
              return version;
            }

            @Override
            public void finishTrial(
                Long versionId,
                BigDecimal totalCost,
                int partItemCount,
                int costItemCount) {}
          };

      executor =
          new QuoteCostRunTaskExecutor(
              oaFormMapper,
              oaFormItemMapper,
              costRunPartItemMapper,
              costRunTaskMapper,
              materialMasterSyncService,
              pricePrepareReadinessService,
              materialPriceRouterService,
              linkedPriceEnsureService,
              costRunEngine,
              costRunResultWriter,
              quoteCostRunVersionService);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T mapperProxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    return 0;
  }

  private static CostRunTask quoteTask() {
    CostRunTask task = new CostRunTask();
    task.setId(1L);
    task.setBatchNo("CRQ-1");
    task.setScene("QUOTE");
    task.setOaNo("OA-1");
    task.setOaFormItemId(11L);
    task.setProductCode("PROD-1");
    task.setPackageMethod("BOX");
    task.setCustomerName("ACME");
    task.setBusinessUnitType("COMMERCIAL");
    task.setPricingMonth("2026-05");
    task.setCalcObjectKey("QUOTE:11");
    return task;
  }

  private static OaForm form() {
    OaForm form = new OaForm();
    form.setId(10L);
    form.setOaNo("OA-1");
    form.setCustomer("ACME");
    form.setBusinessUnitType("COMMERCIAL");
    return form;
  }

  private static OaFormItem item() {
    OaFormItem item = new OaFormItem();
    item.setId(11L);
    item.setOaFormId(10L);
    item.setMaterialNo("PROD-1");
    item.setPackageMethod("BOX");
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private static CostRunPartItemDto part(String partCode) {
    CostRunPartItemDto part = new CostRunPartItemDto();
    part.setOaNo("OA-1");
    part.setProductCode("PROD-1");
    part.setPartCode(partCode);
    return part;
  }

  private static CostRunObjectResult result() {
    CostRunResultDto resultDto = new CostRunResultDto();
    resultDto.setTotalCost(new BigDecimal("123.45"));
    return CostRunObjectResult.of(null, null, resultDto, List.of(part("PART-1")), List.of());
  }
}
