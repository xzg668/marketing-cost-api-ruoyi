package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One checker per database/batch. The session mutex never locks business rows or waits in the pool. */
@Component
public class MonthlyRepriceCheckCoordinator {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MonthlyRepriceCheckCoordinator.class);
  private final DataSource dataSource;
  private final MonthlyRepriceContractMapper batches;
  private final MonthlyRepriceCheckService checks;
  private final MonthlyRepriceContractService transitions;

  public MonthlyRepriceCheckCoordinator(DataSource dataSource,MonthlyRepriceContractMapper batches,
      MonthlyRepriceCheckService checks,MonthlyRepriceContractService transitions) {
    this.dataSource=dataSource;this.batches=batches;this.checks=checks;this.transitions=transitions;
  }

  public boolean check(String no) {
    if(TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("完整调价检查不能在业务事务内运行");
    try(var connection=dataSource.getConnection()) {
      connection.setAutoCommit(true);
      String key=key(connection.getCatalog(),no);
      if(!lock(connection,"GET_LOCK",key)) return false;
      try {
        // Another process may have finished between the candidate scan and acquiring this mutex.
        var batch=batches.readBatch(no);
        if(batch==null || !"CHECKING".equals(batch.getStatus())) return false;
        log.info("调价检查开始 batch={} revision={}",no,batch.getResultRevision());
        var report=checks.verify(batch);
        var current=batches.readBatch(no);
        if(current==null || !"CHECKING".equals(current.getStatus())
            || !java.util.Objects.equals(batch.getResultRevision(),current.getResultRevision())) return false;
        transitions.recordCheck(report);
        log.info("调价检查完成 batch={} revision={} passed={} checkedSuccess={}",no,report.resultRevision(),report.passed(),report.checkedSuccess());
        return true;
      } finally {
        try {
          if(!lock(connection,"RELEASE_LOCK",key)) connection.abort(Runnable::run);
        } catch(SQLException failure) {
          connection.abort(Runnable::run);throw failure;
        }
      }
    } catch(SQLException failure) {throw new DataAccessResourceFailureException("无法协调调价检查",failure);}
  }

  private static boolean lock(Connection connection,String operation,String key) throws SQLException {
    String sql="GET_LOCK".equals(operation)?"SELECT GET_LOCK(?,0)":"SELECT RELEASE_LOCK(?)";
    try(var statement=connection.prepareStatement(sql)) {
      statement.setString(1,key);
      try(var result=statement.executeQuery()) {return result.next() && result.getInt(1)==1;}
    }
  }
  private static String key(String database,String no) {
    try {
      return "reprice-check:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest((database+":"+no).getBytes(StandardCharsets.UTF_8))).substring(0,48);
    } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
  }
}
