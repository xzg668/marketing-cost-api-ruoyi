package com.sanhua.marketingcost.service.reprice;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** All instances sharing a database must use the same admission policy. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "monthly.reprice.capacity")
public class MonthlyRepriceCapacity {
  private int maxActive = 4;
  private int claimsPerSecond = 100;
  private int reservedConnections = 32;

  Rules rules() { return new Rules(maxActive, claimsPerSecond, reservedConnections); }
  public String policyIdentity() { return rules().identity(); }

  record Rules(int maxActive, int claimsPerSecond, int reservedConnections) {
    // Product advisory lock + outer persistence + independent group transaction + monthly pool.
    static final int CONNECTIONS_PER_OBJECT = 4;

    Rules {
      if (maxActive < 1 || maxActive > 64 || claimsPerSecond < 1 || claimsPerSecond > 10000
          || reservedConnections < 0)
        throw new IllegalArgumentException("调价总并发须1～64、每秒领取1～10000、预留连接不能为负数");
    }

    String identity() { return "v1:" + maxActive + ":" + claimsPerSecond + ":" + reservedConnections; }

    int allowance(int requested, long active, long serverMaximum, long connected) {
      // Existing active work may still grow to its peak. Count it conservatively even if some
      // of its connections already appear in Threads_connected; never spend the quotation reserve.
      long headroom = serverMaximum - reservedConnections - connected - active * CONNECTIONS_PER_OBJECT;
      return (int) Math.max(0, Math.min(Math.min(requested, claimsPerSecond),
          Math.min(maxActive - active, headroom / CONNECTIONS_PER_OBJECT)));
    }
  }
}
