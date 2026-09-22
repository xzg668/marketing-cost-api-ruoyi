package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketExchangeRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketExchangeResponse;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OA 确认实际外部人员后，服务端再校验当前账号与任务；不接受调用者自报 userId。 */
@Service
public class TechnicalDataAccessTicketServiceImpl implements TechnicalDataAccessTicketService {
  private static final int SESSION_SECONDS = 900;
  private final TechnicalDataOaGateway gateway;
  private final TechnicalDataOaUserDirectory users;
  private final QuoteTechTaskMapper tasks;
  private final com.sanhua.marketingcost.mapper.QuoteTechModuleMapper modules;
  private final JwtUtils jwt;
  private final TechnicalDataAuditLogService audit;
  private final OaMessageCodec codec;
  private final TransactionTemplate transaction;

  public TechnicalDataAccessTicketServiceImpl(TechnicalDataOaGateway gateway, TechnicalDataOaUserDirectory users,
      QuoteTechTaskMapper tasks, JwtUtils jwt, TechnicalDataAuditLogService audit, OaMessageCodec codec,
      PlatformTransactionManager transactionManager, com.sanhua.marketingcost.mapper.QuoteTechModuleMapper modules) {
    this.modules = modules;
    this.gateway = gateway; this.users = users; this.tasks = tasks; this.jwt = jwt; this.audit = audit; this.codec = codec;
    transaction = new TransactionTemplate(transactionManager);
  }

  @Override
  public TechnicalDataAccessTicketExchangeResponse exchange(TechnicalDataAccessTicketExchangeRequest request) {
    if (request == null || request.taskId() == null || request.taskId() <= 0 || request.code() == null
        || request.code().isBlank() || request.code().length() > 256) throw new IllegalArgumentException("请提供任务和一次性 OA 身份码");
    var peer = gateway.peer();
    // HTTP 在事务外；兑换回来后再锁定任务，避免转派并发使旧负责人获得办理权限。
    var identity = gateway.exchange(request.taskId(), request.code());
    return transaction.execute(status -> {
      var task = tasks.selectByIdForUpdate(identity.taskId());
      if (task == null || !Objects.equals(task.getActiveFlag(), 1) || "CANCELLED".equals(task.getTaskStatus())
          || !peer.sourceSystem().equals(task.getExternalSystem()) || !peer.environment().equals(task.getOaEnvironment())
          || !peer.businessUnits().contains(task.getBusinessUnitType())) throw forbidden("当前链接不属于此环境中的有效任务");
      var actor = users.actor(peer, identity.externalUserId());
      if (!actor.canEdit() || !actor.canReadTask(task, modules.selectByTaskId(task.getId()))) throw forbidden("当前 OA 人员无权办理此任务");
      var user = users.activeUser(actor.userId());
      String nonce = codec.canonicalHash(Map.of("source", peer.sourceSystem(), "environment", peer.environment(), "code", request.code()));
      audit.record(task, null, null, "OA_IDENTITY_CODE_CONSUMED", null, "user=" + user.getUserId(),
          "OA 一次性身份兑换", actor, "oa-code:" + nonce, "TD-OA-CODE:" + nonce);
      String token = jwt.generateTechnicalDataSessionToken(user.getUserName(), task.getBusinessUnitType(), task.getId(),
          peer.environment(), SESSION_SECONDS);
      return new TechnicalDataAccessTicketExchangeResponse(token, task.getId(), user.getUserId(), "TASK_ENTRY",
          Instant.now().plusSeconds(SESSION_SECONDS), "/technical-data-access/tasks/" + task.getId());
    });
  }

  private TechnicalDataTaskException forbidden(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, message); }
}
