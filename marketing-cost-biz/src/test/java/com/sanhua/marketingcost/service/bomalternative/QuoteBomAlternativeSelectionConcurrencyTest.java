package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

@DisplayName("QBA-05 报价BOM选择并发和事务")
class QuoteBomAlternativeSelectionConcurrencyTest {

  @Test
  @DisplayName("旧expectedSelectionVersion不能覆盖别人已经提交的新选择")
  void rejectsStaleExpectedVersion() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    BomAlternativeGroup group = support.groupWithTwoAlternatives();
    support.service.ensureDefault(support.scope(), group);
    support.service.save(support.command("ALT", 1), group);
    QuoteBomAlternativeSelectionCommand staleCommand =
        new QuoteBomAlternativeSelectionCommand(
            support.scope(),
            QuoteBomAlternativeSelectionTestSupport.GROUP_KEY,
            "ALT-2",
            1,
            "BUILD-1",
            "other-user",
            "并发覆盖");

    assertThatThrownBy(() -> support.service.save(staleCommand, group))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting("code")
        .isEqualTo("ALT_SELECTION_CONFLICT");
    assertThat(support.service.findCurrent(
            support.scope(), QuoteBomAlternativeSelectionTestSupport.GROUP_KEY)
        .selectedMaterialCode())
        .isEqualTo("ALT");
  }

  @Test
  @DisplayName("所有写入口声明任意异常回滚")
  void writeMethodsDeclareRollbackForException() throws Exception {
    Method ensureDefault =
        QuoteBomAlternativeSelectionServiceImpl.class.getMethod(
            "ensureDefault",
            QuoteBomAlternativeSelectionScope.class,
            BomAlternativeGroup.class);
    Method save =
        QuoteBomAlternativeSelectionServiceImpl.class.getMethod(
            "save",
            QuoteBomAlternativeSelectionCommand.class,
            BomAlternativeGroup.class);
    Method reconcile =
        QuoteBomAlternativeSelectionServiceImpl.class.getMethod(
            "reconcile",
            QuoteBomAlternativeSelectionScope.class,
            BomAlternativeGroup.class);
    Method synchronize =
        QuoteBomAlternativeSelectionServiceImpl.class.getMethod(
            "synchronize",
            QuoteBomAlternativeSelectionScope.class,
            java.util.List.class);

    assertRollback(ensureDefault);
    assertRollback(save);
    assertRollback(reconcile);
    assertRollback(synchronize);
  }

  @Test
  @DisplayName("旧版本已切历史但新版本写入失败时事务管理器执行回滚")
  void insertFailureTriggersTransactionRollback() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    support.service.ensureDefault(support.scope(), support.group());
    support.repository.failNextInsert = true;
    CountingTransactionManager transactionManager = new CountingTransactionManager();
    TransactionInterceptor interceptor =
        new TransactionInterceptor(
            transactionManager, new AnnotationTransactionAttributeSource());
    ProxyFactory proxyFactory = new ProxyFactory(support.service);
    proxyFactory.addAdvice(interceptor);
    QuoteBomAlternativeSelectionService proxy =
        (QuoteBomAlternativeSelectionService) proxyFactory.getProxy();

    assertThatThrownBy(
            () -> proxy.save(support.command("ALT", 1), support.group()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("模拟新版本写入失败");

    assertThat(transactionManager.rollbackCount.get()).isEqualTo(1);
    assertThat(transactionManager.commitCount.get()).isZero();
  }

  private void assertRollback(Method method) {
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
    protected void doBegin(
        Object transaction, TransactionDefinition definition) {
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
