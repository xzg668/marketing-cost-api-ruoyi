package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.mapper.MonthlyRepriceReuseMapper;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost;
import com.sanhua.marketingcost.service.costing.ResolvedOrdinaryCost;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable arithmetic publication. Consumer preparation and saving remain in their own transaction. */
@Service
public class MonthlyRepriceSharedNumbers {
  public record Numbers(int schemaVersion, ResolvedOrdinaryCost.Calculated ordinary,
      MonthlyProductCost.Numbers monthly) {
    public Numbers {
      if (schemaVersion != 1) throw new IllegalArgumentException("未知共享成本数值版本");
      Objects.requireNonNull(ordinary); Objects.requireNonNull(monthly);
    }
  }
  public record Published(long groupId, Numbers numbers) {}
  public static final class GroupBusy extends TransientDataAccessResourceException {
    public GroupBusy(String message, Throwable cause) { super(message, cause); }
  }

  private final MonthlyRepriceComputeGroups groups;
  private final MonthlyRepriceReuseMapper mapper;
  private final MonthlyPriceDocumentCodec codec;
  private final TransactionTemplate withoutTransaction;
  private final MonthlyRepriceGroupLeases leases;
  private final long waitNanos;

  public MonthlyRepriceSharedNumbers(MonthlyRepriceComputeGroups groups, MonthlyRepriceReuseMapper mapper,
      MonthlyPriceDocumentCodec codec, PlatformTransactionManager manager, MonthlyRepriceGroupLeases leases,
      @Value("${monthly.reprice.compute-wait-millis:5000}") long waitMillis) {
    this.groups = groups; this.mapper = mapper; this.codec = codec;
    this.leases = leases;
    if (waitMillis < 1 || waitMillis > 60000) throw new IllegalArgumentException("共享计算等待上限须为1至60000毫秒");
    waitNanos = TimeUnit.MILLISECONDS.toNanos(waitMillis);
    withoutTransaction = new TransactionTemplate(manager);
    withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
  }

  /** The supplier is pure arithmetic over resolved values; it must perform no database reads/writes. */
  public Published getOrCalculate(String no, MonthlyRepriceInputDocument input, Supplier<Numbers> calculate) {
    // Release the batch registration lock before taking a group lock or doing arithmetic.
    var registered = groups.register(no, input);
    return withoutTransaction.execute(tx -> calculateOrAwait(registered.id(), input, calculate));
  }

  private Published calculateOrAwait(long groupId, MonthlyRepriceInputDocument input, Supplier<Numbers> calculate) {
    long started = System.nanoTime();
    while (true) {
      var claim = leases.claim(groupId, input);
      if (claim instanceof MonthlyRepriceGroupLeases.Published saved)
        return new Published(groupId, codec.decode(saved.document(), Numbers.class));
      if (claim instanceof MonthlyRepriceGroupLeases.Acquired acquired) {
        try {
          var numbers = Objects.requireNonNull(calculate.get());
          leases.publish(acquired.token(), codec.compact(codec.encode(numbers)));
          return new Published(groupId, numbers);
        } catch (RuntimeException failure) {
          try { leases.fail(acquired.token(), failure.getMessage()); }
          catch (RuntimeException recordingFailure) { failure.addSuppressed(recordingFailure); }
          throw failure;
        }
      }
      long remaining = waitNanos - (System.nanoTime() - started);
      if (remaining <= 0) throw new GroupBusy("共享成本由其他实例计算，等待超时后可重试", null);
      try { Thread.sleep(Math.min(25, TimeUnit.NANOSECONDS.toMillis(remaining) + 1)); }
      catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new GroupBusy("等待共享成本已中断", interrupted);
      }
    }
  }

  /** Called only after this customer's own validations and save, inside its completion transaction. */
  public void bind(String no, String objectKey, long groupId) {
    // A completed customer is immutable. Accept exact redelivery without updating it again.
    // This also handles JDBC configurations that report zero for an unchanged pending binding.
    int updated = mapper.bindGroup(no, objectKey, groupId);
    if (updated == 1 || (updated == 0 && mapper.alreadyBound(no, objectKey, groupId))) return;
    throw new IllegalStateException("共享成本与客户月度对象范围不一致");
  }
}
