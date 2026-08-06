package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorMonthlyUpsertService;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@DisplayName("PLI2-06 类型2因素写入事务和业务单元隔离")
class PriceLinkedType2FactorImportTransactionTest {

  @Test
  @DisplayName("默认保留和明确覆盖两个写入入口都声明异常回滚事务边界")
  void bothUpsertEntrypointsDeclareRollbackForException() throws Exception {
    Method defaultKeepMethod = PriceLinkedType2FactorMonthlyUpsertServiceImpl.class.getMethod(
        "upsert",
        com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult.class,
        String.class,
        String.class,
        String.class,
        Long.class);
    Method explicitStrategyMethod = PriceLinkedType2FactorMonthlyUpsertServiceImpl.class.getMethod(
        "upsert",
        com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult.class,
        String.class,
        String.class,
        String.class,
        Long.class,
        String.class);

    assertRollbackForException(defaultKeepMethod);
    assertRollbackForException(explicitStrategyMethod);
  }

  @Test
  @DisplayName("来源行保存异常向外抛出并触发事务管理器回滚")
  void rowReferenceFailureTriggersTransactionRollback() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    when(support.factorUploadBatchService.saveRowRefs(
        anyLong(),
        any(FactorWorkbookParseResult.class),
        any(FactorMonthlyPriceUpsertResult.class)))
        .thenThrow(new IllegalStateException("模拟来源行保存失败"));
    CountingTransactionManager transactionManager =
        new CountingTransactionManager();
    TransactionInterceptor interceptor = new TransactionInterceptor(
        transactionManager, new AnnotationTransactionAttributeSource());
    ProxyFactory proxyFactory = new ProxyFactory(support.service);
    proxyFactory.addAdvice(interceptor);
    PriceLinkedType2FactorMonthlyUpsertService proxy =
        (PriceLinkedType2FactorMonthlyUpsertService) proxyFactory.getProxy();

    assertThatThrownBy(() -> proxy.upsert(
        support.workbook(support.factor(
            2, "1", "铜价", "1#Cu", "平均价", "90")),
        "2026-07",
        "COMMERCIAL",
        "tester",
        99031L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("模拟来源行保存失败");

    assertThat(transactionManager.rollbackCount.get()).isEqualTo(1);
    assertThat(transactionManager.commitCount.get()).isZero();
  }

  @Test
  @DisplayName("其他业务单元的同名因素不会被复用")
  void sameFactorInOtherBusinessUnitDoesNotLeak() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    support.addIdentity(901L, "BU-B", "1", "铜价", "1#Cu", "平均价");
    support.addMonthlyPrice(902L, 901L, "2026-07", "90");

    FactorMonthlyPriceUpsertResult result = support.service.upsert(
        support.workbook(support.factor(
            2, "1", "铜价", "1#Cu", "平均价", "90")),
        "2026-07",
        "BU-A",
        "tester",
        99032L);

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getIdentityCreatedCount()).isEqualTo(1);
    assertThat(result.getRows().getFirst().getFactorIdentityId()).isNotEqualTo(901L);
    assertThat(support.identities)
        .extracting("businessUnitType")
        .containsExactlyInAnyOrder("BU-B", "BU-A");
    assertThat(support.monthlyPrices).hasSize(2);
  }

  private void assertRollbackForException(Method method) {
    Transactional annotation = method.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.rollbackFor()).contains(Exception.class);
  }

  private static final class CountingTransactionManager
      extends AbstractPlatformTransactionManager {

    private final AtomicInteger commitCount = new AtomicInteger();
    private final AtomicInteger rollbackCount = new AtomicInteger();

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      // 测试只统计事务结果。
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commitCount.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbackCount.incrementAndGet();
    }
  }
}
