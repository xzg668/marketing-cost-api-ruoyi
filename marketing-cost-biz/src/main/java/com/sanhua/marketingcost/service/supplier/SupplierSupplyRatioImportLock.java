package com.sanhua.marketingcost.service.supplier;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One import per database/business unit, including empty relations. The transaction's connection
 * owns the lock until commit/rollback: no extra lock table, fake audit rows or pool starvation. */
@Component
public class SupplierSupplyRatioImportLock {
  private final DataSource dataSource;
  public SupplierSupplyRatioImportLock(DataSource dataSource) { this.dataSource = dataSource; }

  public void acquire(String unit) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("供货关系导入必须在事务内加锁");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      String key = "ssr:" + MonthlyPriceDocumentCodec.sha256(connection.getCatalog()+":"+unit).substring(0,60);
      try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,30)")) {
        statement.setString(1,key);
        try (var result=statement.executeQuery()) {
          if (!result.next() || result.getInt(1)!=1)
            throw new CannotAcquireLockException("本业务单元正在导入供货关系，请稍后重试");
        }
      }
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) { release(connection,key); }
      });
    } catch (SQLException ex) {
      throw new DataAccessResourceFailureException("供货关系导入加锁失败",ex);
    }
  }

  private void release(Connection connection,String key) {
    try (var statement=connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setString(1,key);
      try (var result=statement.executeQuery()) {
        if (!result.next() || result.getInt(1)!=1) throw new SQLException("供货关系锁释放失败");
      }
    } catch (SQLException ex) {
      // A pooled connection must never return with a surviving named lock.
      try { connection.abort(Runnable::run); }
      catch (SQLException abort) { ex.addSuppressed(abort); }
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("供货关系互斥连接已作废",ex);
    }
  }
}
