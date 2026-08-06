package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.service.PriceLinkedType2ImportOrchestrator;
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

@DisplayName("PLI2-10 类型2确认导入事务")
class PriceLinkedType2ImportTransactionTest {

  @Test
  @DisplayName("确认入口声明异常回滚事务边界")
  void confirmDeclaresRollbackForException() throws Exception {
    Method method = PriceLinkedType2ImportOrchestratorImpl.class.getMethod(
        "confirm", com.sanhua.marketingcost.dto.PriceLinkedImportCommand.class);
    Transactional annotation = method.getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.rollbackFor()).contains(Exception.class);
  }

  @Test
  @DisplayName("联动价版本或绑定保存异常会回滚已写批次和因素")
  void bindingFailureRollsBackWholeConfirm() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();
    when(support.importBasisService.save(any()))
        .thenThrow(new IllegalStateException("模拟绑定保存失败"));
    CountingTransactionManager transactionManager = new CountingTransactionManager();
    TransactionInterceptor interceptor = new TransactionInterceptor(
        transactionManager, new AnnotationTransactionAttributeSource());
    ProxyFactory proxyFactory = new ProxyFactory(support.orchestrator);
    proxyFactory.addAdvice(interceptor);
    PriceLinkedType2ImportOrchestrator proxy =
        (PriceLinkedType2ImportOrchestrator) proxyFactory.getProxy();
    byte[] bytes = {1, 2, 3, 4};

    assertThatThrownBy(() -> proxy.confirm(
        support.command(bytes, support.hash(bytes), "COMMERCIAL", false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("绑定保存失败");

    assertThat(transactionManager.rollbackCount.get()).isEqualTo(1);
    assertThat(transactionManager.commitCount.get()).isZero();
    verify(support.batchService).createFactorBatch(any());
    verify(support.factorUpsertService).upsert(
        any(), any(), any(), any(), any(), any());
    verify(support.importBasisService).save(any());
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
      // 仅统计事务结果。
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
