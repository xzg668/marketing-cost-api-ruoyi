package com.sanhua.marketingcost.integration.oa.oauth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sanhua.marketingcost.integration.oa.OaIntegrationProperties;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryProperties;
import com.sanhua.marketingcost.security.JwtUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 单实例部署的短时授权中转。只缓存一次性 state/交接票据，不持久化个人 OA token。 */
@Service
// 人员免登由 Web 后端承接，核算 worker 不加载浏览器会话及其 JWT 依赖。
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OaOAuthService {
  public record BeginRequest(Long formId, Long taskId, String submission, String browserKey) {}
  public record BeginResponse(String authorizeUrl) {}
  public record ExchangeRequest(String ticket, String verifier) {}
  public record SessionResponse(String accessToken, String entryPath, Instant expiresAt) {}
  private record Entry(long formId, String businessUnit, String path) {}
  private record Pending(Entry entry, String challenge) {}
  private record Handoff(Pending pending, OaOAuthClient.Identity identity, Instant expiresAt) {}
  private final Cache<String, Pending> states = Caffeine.newBuilder().maximumSize(10000)
      .expireAfterWrite(Duration.ofMinutes(10)).build();
  private final Cache<String, Handoff> tickets = Caffeine.newBuilder().maximumSize(10000)
      .expireAfterWrite(Duration.ofSeconds(60)).build();
  private final SecureRandom random = new SecureRandom();
  private final OaOAuthClient client;
  private final OaIntegrationProperties integration;
  private final OaPersonDirectoryProperties directory;
  private final JdbcTemplate jdbc;
  private final JwtUtils jwt;

  public OaOAuthService(OaOAuthClient client, OaIntegrationProperties integration,
      OaPersonDirectoryProperties directory, JdbcTemplate jdbc, JwtUtils jwt) {
    this.client = client; this.integration = integration; this.directory = directory;
    this.jdbc = jdbc; this.jwt = jwt;
  }

  public BeginResponse begin(BeginRequest request) {
    if (request == null || request.browserKey() == null
        || !request.browserKey().matches("[A-Za-z0-9_-]{43}")) throw invalid();
    Entry entry = entry(request.formId(), request.taskId(), request.submission());
    landingUrl();
    String state = nonce();
    String url = client.authorizeUrl(state);
    states.put(state, new Pending(entry, hash(request.browserKey())));
    return new BeginResponse(url);
  }

  public String callback(String code, String state) {
    Pending pending = validNonce(state) ? states.asMap().remove(state) : null;
    if (pending == null) throw invalid();
    var identity = client.authenticate(code);
    String ticket = nonce();
    tickets.put(ticket, new Handoff(pending, identity, Instant.now().plusSeconds(identity.expiresIn())));
    // URL 只携带 60 秒一次性交接票据；JWT 和 OA token 都不进入 URL。
    return landingUrl() + "#ticket=" + ticket;
  }

  public SessionResponse exchange(ExchangeRequest request) {
    if (request == null || !validNonce(request.ticket()) || !validNonce(request.verifier())) throw invalid();
    var handoff = tickets.getIfPresent(request.ticket());
    if (handoff == null || !MessageDigest.isEqual(hash(request.verifier()).getBytes(StandardCharsets.US_ASCII),
        handoff.pending().challenge().getBytes(StandardCharsets.US_ASCII))
        || !tickets.asMap().remove(request.ticket(), handoff)) throw invalid();
    long remaining = Duration.between(Instant.now(), handoff.expiresAt()).getSeconds();
    if (remaining <= 0) throw invalid();
    Entry entry = entry(handoff.pending().entry().formId(), null, null);
    var person = handoff.identity();
    String token = jwt.generateOaSessionToken(person.employeeNo(), person.name(), entry.businessUnit(),
        entry.formId(), directory.getEnvironment(), remaining);
    return new SessionResponse(token, handoff.pending().entry().path(), handoff.expiresAt());
  }

  public String landingUrl() {
    String base = integration.getOutbound().getFrontendBaseUrl();
    OaOAuthProperties.validateUrl(base);
    return base.replaceAll("/+$", "") + "/oa-login";
  }

  private Entry entry(Long formId, Long taskId, String submission) {
    if ((formId == null) == (taskId == null) || formId != null && formId <= 0 || taskId != null && taskId <= 0
        || submission != null && !submission.matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
    if (taskId != null) {
      List<Long> forms = jdbc.queryForList("SELECT oa_form_id FROM lp_quote_tech_task WHERE id=? AND active_flag=1", Long.class, taskId);
      if (forms.size() != 1) throw invalid();
      formId = forms.getFirst();
    }
    var units = jdbc.queryForList("""
        SELECT f.business_unit_type FROM oa_form f JOIN lp_oa_quote_document d ON d.oa_form_id=f.id
        WHERE f.id=? AND f.deleted=0 AND d.source_system=? AND d.environment=?
        """, String.class, formId, directory.getSourceSystem(), directory.getEnvironment());
    if (units.size() != 1) throw new OaOAuthException("INVALID_DOCUMENT", "补录链接对应的 OA 单据不存在或不属于当前环境");
    String path = "/collaboration/technical-data/forms/" + formId;
    if (submission != null) path += "?submission=" + submission;
    return new Entry(formId, units.getFirst(), path);
  }

  private String nonce() {
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
  private static boolean validNonce(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
  public static String hash(String value) {
    try { return Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
    catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
  }
  private static OaOAuthException invalid() {
    return new OaOAuthException("INVALID_LOGIN", "免登校验无效、已使用或已过期，请从 OA 重新进入");
  }
}
