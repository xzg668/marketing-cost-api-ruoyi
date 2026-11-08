package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.mapper.MonthlyRepriceReuseMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceReuseMapper.Ownership;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short database-clock transactions; a lease never carries the arithmetic transaction. */
@Service
public final class MonthlyRepriceGroupLeases {
  public sealed interface Claim permits Acquired, Published, Busy {}
  public record Acquired(Token token) implements Claim {}
  public record Published(String document) implements Claim {}
  public enum Busy implements Claim { INSTANCE }
  public record Token(Ownership owner, MonthlyRepriceInputDocument input) {
    public Token { Objects.requireNonNull(owner); Objects.requireNonNull(input); }
  }
  public static final class LeaseLost extends TransientDataAccessResourceException {
    public LeaseLost() { super("共享计算组租约或执行轮次已失效，迟到结果未发布"); }
  }

  private final MonthlyRepriceReuseMapper mapper;
  private final TransactionTemplate transaction;
  private final String worker;
  private final int seconds;

  public MonthlyRepriceGroupLeases(MonthlyRepriceReuseMapper mapper, PlatformTransactionManager manager,
      MonthlyRepriceInstance instance,
      @Value("${monthly.reprice.compute-lease-seconds:30}") int seconds) {
    this.mapper = mapper;
    if (seconds < 1 || seconds > 300)
      throw new IllegalArgumentException("共享计算租约须为1至300秒");
    worker = instance.value();
    this.seconds = seconds;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(15);
  }

  public Claim claim(long groupId, MonthlyRepriceInputDocument input) {
    return transaction.execute(tx -> {
      var group = mapper.lockGroup(groupId);
      requireInput(group, input);
      if ("SUCCESS".equals(group.status()))
        return new Published(Objects.requireNonNull(mapper.readResult(groupId), "成功计算组缺少结果"));
      if (mapper.claim(groupId, worker, seconds) != 1) return Busy.INSTANCE;
      var claimed = Objects.requireNonNull(mapper.readGroup(groupId));
      var owner = new Ownership(groupId, worker, claimed.executionNo(), input.digest(),
          MonthlyRepriceInputDocument.SCHEMA_VERSION, input.scope().algorithmVersion(), input.scope().feePolicyVersion());
      return new Acquired(new Token(owner, input));
    });
  }

  public void publish(Token token, String document) {
    transaction.executeWithoutResult(tx -> {
      requireInput(mapper.lockGroup(token.owner().groupId()), token.input());
      if (mapper.publish(token.owner(), document) != 1) throw new LeaseLost();
    });
  }

  /** A failed or late caller may only update its still-live execution. */
  public boolean fail(Token token, String reason) {
    return Boolean.TRUE.equals(transaction.execute(tx -> {
      requireInput(mapper.lockGroup(token.owner().groupId()), token.input());
      String message = reason == null || reason.isBlank() ? "共享成本计算失败" : reason;
      message = message.codePoints().limit(1000).collect(StringBuilder::new,
          StringBuilder::appendCodePoint, StringBuilder::append).toString();
      return mapper.fail(token.owner(), message) == 1;
    }));
  }

  private static void requireInput(MonthlyRepriceReuseMapper.Group group, MonthlyRepriceInputDocument input) {
    if (group == null || !input.matchesStored(group.inputDigest(), group.inputDocument())
        || group.inputSchemaVersion() != MonthlyRepriceInputDocument.SCHEMA_VERSION
        || !group.productCode().equals(input.scope().productCode())
        || !group.algorithmVersion().equals(input.scope().algorithmVersion())
        || !group.feePolicyVersion().equals(input.scope().feePolicyVersion()))
      throw new IllegalStateException("共享成本的完整输入或算法已变化");
  }
}
