package com.sanhua.marketingcost.service.pricing.monthly;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only observation of both application and independent monthly pools. */
public final class MonthlyAcceptanceSqlDriver extends com.mysql.cj.jdbc.Driver {
  private static final AtomicLong calls = new AtomicLong();
  private static final Map<String, AtomicLong> sources = new ConcurrentHashMap<>();
  private static final Map<String, AtomicLong> statements = new ConcurrentHashMap<>();
  static final ThreadLocal<Boolean> loseCommitAcknowledgement = new ThreadLocal<>();
  static final ThreadLocal<Runnable> afterFirstFamilyLock = new ThreadLocal<>();
  static final AtomicLong actualDeadlocks = new AtomicLong();

  public MonthlyAcceptanceSqlDriver() throws SQLException { super(); }

  public static void reset() { calls.set(0); sources.clear(); statements.clear(); }
  public static Map<String, Object> snapshot() {
    var categories = new TreeMap<String, Long>();
    sources.forEach((name, count) -> categories.put(name, count.get()));
    var sql = new TreeMap<String, Long>();
    statements.forEach((name, count) -> sql.put(name, count.get()));
    return Map.of("total", calls.get(), "bySource", categories, "statements", sql);
  }

  @Override public Connection connect(String url, Properties properties) throws SQLException {
    var connection = super.connect(url, properties);
    if (connection == null) return null;
    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
          var value = invoke(connection, method, args);
          if (method.getName().equals("commit") && Boolean.TRUE.equals(loseCommitAcknowledgement.get())) {
            loseCommitAcknowledgement.remove();
            throw new SQLNonTransientConnectionException("MPP13 controlled loss after actual commit", "08006");
          }
          if (value instanceof PreparedStatement statement) {
            String sql = (String) args[0];
            return Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (p, operation, arguments) -> {
                  if (operation.getName().startsWith("execute")) record(sql);
                  try {
                    var result = invoke(statement, operation, arguments);
                    if (operation.getName().startsWith("execute") && sql.contains("FOR UPDATE")
                        && sql.contains("uk_monthly_price_identity") && afterFirstFamilyLock.get() != null) {
                      var after = afterFirstFamilyLock.get(); afterFirstFamilyLock.remove(); after.run();
                    }
                    return result;
                  } catch (SQLException failure) {
                    if (failure.getErrorCode() == 1213) actualDeadlocks.incrementAndGet();
                    throw failure;
                  }
                });
          }
          return value;
        });
  }

  private static void record(String sql) {
    calls.incrementAndGet();
    // Stack attribution includes all helper/selection/factor queries inside an adapter.
    String source = StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
        .filter(name -> name.startsWith("com.sanhua.marketingcost.service.pricing.monthly.Monthly")
            && name.endsWith("Source") || name.endsWith("HouseholdMonthlyPriceSource")
            || name.endsWith("MaterialPricePriorityService"))
        .map(name -> name.substring(name.lastIndexOf('.') + 1)).findFirst().orElse("storage-or-common"));
    sources.computeIfAbsent(source, ignored -> new AtomicLong()).incrementAndGet();
    String normalized = sql.replaceAll("\\s+", " ").trim()
        .replaceAll("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)", "(<batch>)");
    statements.computeIfAbsent(normalized, ignored -> new AtomicLong()).incrementAndGet();
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException failure) { throw failure.getCause(); }
  }
}
