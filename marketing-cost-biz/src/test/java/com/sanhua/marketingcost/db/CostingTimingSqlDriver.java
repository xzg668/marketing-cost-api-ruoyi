package com.sanhua.marketingcost.db;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in test driver: timings and counts only, never SQL parameter values. */
public final class CostingTimingSqlDriver extends com.mysql.cj.jdbc.Driver {
  private static volatile boolean recording;
  private static final Map<String, Metric> metrics = new ConcurrentHashMap<>();
  private static final class Metric {
    final LongAdder count = new LongAdder(), nanos = new LongAdder();
  }
  public CostingTimingSqlDriver() throws SQLException { super(); }
  public static void start() { metrics.clear(); recording = true; }
  public static List<Map<String,Object>> finish() {
    recording = false;
    return metrics.entrySet().stream().sorted(Comparator.comparingLong(
        (Map.Entry<String,Metric> e) -> e.getValue().nanos.sum()).reversed())
        .map(e -> Map.<String,Object>of("sql",e.getKey(),"calls",e.getValue().count.sum(),
            "ms",e.getValue().nanos.sum()/1_000_000.0)).toList();
  }
  @Override public Connection connect(String url, Properties properties) throws SQLException {
    var connection = super.connect(url,properties);
    if (connection == null) return null;
    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
        (proxy,method,args) -> {
          Object result = invoke(connection,method,args);
          if (!(result instanceof Statement statement)) return result;
          String preparedSql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
          Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
          return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},(p,operation,arguments) -> {
            if (!recording || !operation.getName().startsWith("execute")) return invoke(statement,operation,arguments);
            String sql = preparedSql != null ? preparedSql : String.valueOf(arguments[0]);
            String normalized = sql.replaceAll("\\s+"," ").trim().replaceAll("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)","(<batch>)");
            long began = System.nanoTime();
            try { return invoke(statement,operation,arguments); }
            finally {
              var metric = metrics.computeIfAbsent(normalized,ignored -> new Metric());
              metric.count.increment();metric.nanos.add(System.nanoTime()-began);
            }
          });
        });
  }
  private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
    try { return method.invoke(target,args); }
    catch (InvocationTargetException failure) { throw failure.getCause(); }
  }
}
