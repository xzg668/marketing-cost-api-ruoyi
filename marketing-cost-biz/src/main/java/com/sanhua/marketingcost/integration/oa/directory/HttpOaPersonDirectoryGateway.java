package com.sanhua.marketingcost.integration.oa.directory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OA 人员目录适配器。
 *
 * <p>OA 的组织、人员和岗位接口字段差异集中在这里，应用层只接收完整快照。任何分页或人员详情失败都会
 * 使本次加载整体失败，避免把不完整人员目录交给后续事务写入。
 */
@Component
public class HttpOaPersonDirectoryGateway implements OaPersonDirectoryGateway {
  private static final Set<Integer> RETRYABLE_STATUS = Set.of(404, 502, 503, 504);
  private static final String QUERY_ORG = "/openserver/api/hrm/restful/queryOrg";
  private static final String QUERY_EMPLOYEE = "/openserver/api/hrm/restful/queryEmployee";
  private static final String FIND_USER = "/openserver/user/v3/findUser";

  private final OaPersonDirectoryProperties properties;
  private final ObjectMapper json;
  private final HttpClient http;

  @Autowired
  public HttpOaPersonDirectoryGateway(OaPersonDirectoryProperties properties, ObjectMapper json) {
    this(properties, json, HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build());
  }

  HttpOaPersonDirectoryGateway(
      OaPersonDirectoryProperties properties, ObjectMapper json, HttpClient http) {
    this.properties = properties;
    this.json = json;
    this.http = http;
  }

  @Override
  public OaDirectorySnapshot load() {
    if (!properties.isEnabled()) {
      throw new OaPersonDirectoryException("OA 人员目录同步未启用");
    }
    String token = acquireAccessToken();
    List<JsonNode> organizations = fetchAll(token, QUERY_ORG, Map.of());
    Map<String, List<String>> organizationPaths = buildOrganizationPaths(organizations);
    TargetResolution targets = resolveTargets(organizationPaths);
    if (targets.resolved().isEmpty()) {
      throw new OaPersonDirectoryException("目标大部门在 OA 组织树中均未找到");
    }

    List<JsonNode> employees = fetchAll(token, QUERY_EMPLOYEE,
        Map.of("returnFieldList", List.of("id", "department", "username", "job_num")));
    Map<PersonKey, MutablePerson> selected = selectPeople(employees, organizationPaths, targets.resolved());
    addPersonDetails(token, selected);
    List<OaDirectoryPerson> people = selected.values().stream()
        .map(MutablePerson::toDirectoryPerson)
        .sorted(Comparator.comparing(OaDirectoryPerson::name)
            .thenComparing(OaDirectoryPerson::employeeNo))
        .toList();
    return new OaDirectorySnapshot(
        organizations.size(), employees.size(), people, targets.missing());
  }

  private String acquireAccessToken() {
    String authorizeQuery = "corpid=" + encode(properties.getCorpId()) + "&response_type=code";
    HttpRequest authorize = requestBuilder(properties.getAuthBaseUrl(),
        "/openserver/oauth2/authorize?" + authorizeQuery)
        .GET().build();
    JsonNode authorizeResult = execute(authorize, "JK-01 获取授权码");
    requireTopLevelSuccess(authorizeResult, "JK-01");
    String code = text(authorizeResult, "code");
    if (!StringUtils.hasText(code)) {
      throw new OaPersonDirectoryException("JK-01 未返回授权码");
    }

    String body = "app_key=" + encode(properties.getAppKey())
        + "&app_secret=" + encode(properties.getAppSecret())
        + "&grant_type=authorization_code&code=" + encode(code);
    HttpRequest token = requestBuilder(properties.getAuthBaseUrl(), "/openserver/oauth2/access_token")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    JsonNode tokenResult = execute(token, "JK-02 获取访问令牌");
    requireTopLevelSuccess(tokenResult, "JK-02");
    String accessToken = text(tokenResult, "accessToken");
    if (!StringUtils.hasText(accessToken)) {
      accessToken = text(tokenResult, "acessToken");
    }
    if (!StringUtils.hasText(accessToken)) {
      throw new OaPersonDirectoryException("JK-02 未返回访问令牌");
    }
    return accessToken;
  }

  private List<JsonNode> fetchAll(String token, String endpoint, Map<String, Object> extra) {
    int pageSize = properties.getPageSize();
    Map<String, Object> requestBody = new LinkedHashMap<>(extra);
    requestBody.put("current", 1);
    requestBody.put("pageSize", pageSize);
    Page first = requestPage(token, endpoint, requestBody, 1);
    List<JsonNode> rows = new ArrayList<>(first.rows());
    int pages = (first.total() + pageSize - 1) / pageSize;
    for (int current = 2; current <= pages; current++) {
      requestBody.put("current", current);
      rows.addAll(requestPage(token, endpoint, requestBody, current).rows());
    }
    if (rows.size() != first.total()) {
      throw new OaPersonDirectoryException(
          endpoint + " 分页数量与 total 不一致：" + rows.size() + "/" + first.total());
    }
    return rows;
  }

  private Page requestPage(
      String token, String endpoint, Map<String, Object> requestBody, int current) {
    final String body;
    try {
      body = json.writeValueAsString(requestBody);
    } catch (IOException exception) {
      throw new OaPersonDirectoryException("无法生成 OA 分页请求", exception);
    }
    HttpRequest request = requestBuilder(properties.getQueryBaseUrl(),
        endpoint + "?access_token=" + encode(token))
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    JsonNode result = execute(request, endpoint + " 第 " + current + " 页");
    requireMessageSuccess(result, endpoint);
    JsonNode data = result.path("data");
    if (!data.isObject() || !data.path("total").canConvertToInt() || data.path("total").asInt() < 0
        || !data.path("data").isArray()) {
      throw new OaPersonDirectoryException(endpoint + " 未返回有效分页数据");
    }
    List<JsonNode> rows = new ArrayList<>();
    data.path("data").forEach(rows::add);
    return new Page(data.path("total").asInt(), rows);
  }

  private Map<String, List<String>> buildOrganizationPaths(List<JsonNode> organizations) {
    Map<String, JsonNode> byId = new LinkedHashMap<>();
    for (JsonNode organization : organizations) {
      String id = text(organization, "id");
      if (!StringUtils.hasText(id)) {
        throw new OaPersonDirectoryException("OA 组织记录缺少 ID");
      }
      if (byId.putIfAbsent(id, organization) != null) {
        throw new OaPersonDirectoryException("OA 组织 ID 重复：" + id);
      }
    }
    Map<String, List<String>> paths = new LinkedHashMap<>();
    for (String id : byId.keySet()) {
      List<String> reversed = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      String current = id;
      while (byId.containsKey(current)) {
        if (!seen.add(current)) {
          throw new OaPersonDirectoryException("OA 组织层级存在循环");
        }
        JsonNode organization = byId.get(current);
        String name = text(organization, "name");
        if (!StringUtils.hasText(name)) {
          throw new OaPersonDirectoryException("OA 组织名称为空：" + current);
        }
        reversed.add(name);
        current = text(organization, "parent");
      }
      List<String> path = new ArrayList<>(reversed.size());
      for (int index = reversed.size() - 1; index >= 0; index--) {
        path.add(reversed.get(index));
      }
      paths.put(id, List.copyOf(path));
    }
    return paths;
  }

  private TargetResolution resolveTargets(Map<String, List<String>> paths) {
    Map<String, ResolvedTarget> resolved = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();
    for (String target : properties.getTargetDepartments()) {
      List<String> segments = List.of(target.split("/"));
      List<Map.Entry<String, List<String>>> matches = paths.entrySet().stream()
          .filter(entry -> endsWith(entry.getValue(), segments))
          .toList();
      if (matches.size() == 1) {
        var match = matches.getFirst();
        resolved.put(target, new ResolvedTarget(match.getKey(), match.getValue()));
      } else {
        missing.add(target);
      }
    }
    return new TargetResolution(resolved, List.copyOf(missing));
  }

  private Map<PersonKey, MutablePerson> selectPeople(
      List<JsonNode> employees,
      Map<String, List<String>> organizationPaths,
      Map<String, ResolvedTarget> targets) {
    Map<PersonKey, MutablePerson> selected = new LinkedHashMap<>();
    for (JsonNode employee : employees) {
      if (!employee.isObject()) {
        continue;
      }
      String personId = text(employee, "id");
      String employeeNo = text(employee, "job_num");
      String name = text(employee, "username");
      for (String departmentId : stringValues(employee.get("department"))) {
        List<String> personPath = organizationPaths.get(departmentId);
        if (personPath == null) {
          continue;
        }
        for (var targetEntry : targets.entrySet()) {
          ResolvedTarget target = targetEntry.getValue();
          if (!startsWith(personPath, target.path())) {
            continue;
          }
          if (!StringUtils.hasText(personId) || !StringUtils.hasText(employeeNo)
              || !StringUtils.hasText(name)) {
            throw new OaPersonDirectoryException("目标部门人员缺少 OA 人员 ID、工号或姓名");
          }
          PersonKey key = new PersonKey(employeeNo, personId);
          MutablePerson person = selected.computeIfAbsent(key,
              ignored -> new MutablePerson(personId, employeeNo, name));
          if (!person.name.equals(name)) {
            throw new OaPersonDirectoryException("同一 OA 人员返回了不同姓名：" + employeeNo);
          }
          person.targetDepartmentPaths.add(targetEntry.getKey());
          person.actualDepartmentPaths.add(String.join("/", personPath));
          person.oaDepartmentIds.add(departmentId);
          person.targetDepartmentIds.add(target.id());
          person.matchTypes.add(departmentId.equals(target.id()) ? "本部门" : "下级部门");
        }
      }
    }
    Map<String, PersonKey> employeeNumbers = new HashMap<>();
    for (PersonKey key : selected.keySet()) {
      PersonKey previous = employeeNumbers.putIfAbsent(key.employeeNo(), key);
      if (previous != null && !previous.personId().equals(key.personId())) {
        throw new OaPersonDirectoryException("同一工号匹配到多个 OA 人员 ID：" + key.employeeNo());
      }
    }
    return selected;
  }

  private void addPersonDetails(String token, Map<PersonKey, MutablePerson> selected) {
    ExecutorService executor = Executors.newFixedThreadPool(properties.getDetailConcurrency());
    try {
      List<Future<PersonDetail>> futures = selected.keySet().stream()
          .map(key -> executor.submit(() -> fetchPersonDetail(token, key)))
          .toList();
      int index = 0;
      for (MutablePerson person : selected.values()) {
        PersonDetail detail;
        try {
          detail = futures.get(index++).get();
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new OaPersonDirectoryException("OA 人员详情同步被中断", exception);
        } catch (ExecutionException exception) {
          Throwable cause = exception.getCause();
          if (cause instanceof OaPersonDirectoryException directoryException) {
            throw directoryException;
          }
          throw new OaPersonDirectoryException("OA 人员详情同步失败", cause);
        }
        person.positionName = detail.positionName();
        person.positionId = detail.positionId();
        person.employmentStatus = detail.employmentStatus();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private PersonDetail fetchPersonDetail(String token, PersonKey key) {
    String path = FIND_USER + "?access_token=" + encode(token) + "&jobNum=" + encode(key.employeeNo());
    HttpRequest request = requestBuilder(properties.getDetailBaseUrl(), path).GET().build();
    JsonNode result = execute(request, "JK-03 查询工号 " + key.employeeNo());
    requireMessageSuccess(result, "JK-03");
    JsonNode data = result.path("data");
    if (!data.isArray()) {
      throw new OaPersonDirectoryException("JK-03 未返回人员列表：" + key.employeeNo());
    }
    List<JsonNode> matches = new ArrayList<>();
    data.forEach(person -> {
      if (key.employeeNo().equals(text(person, "jobNum"))
          && key.personId().equals(text(person, "userid"))) {
        matches.add(person);
      }
    });
    if (matches.size() != 1) {
      throw new OaPersonDirectoryException("JK-03 未唯一匹配原 OA 人员：" + key.employeeNo());
    }
    JsonNode person = matches.getFirst();
    JsonNode position = person.get("positionInfo");
    if (position != null && !position.isNull() && !position.isObject()) {
      throw new OaPersonDirectoryException("JK-03 岗位信息格式错误：" + key.employeeNo());
    }
    String positionName = position == null ? "" : text(position, "name");
    if (!StringUtils.hasText(positionName)) {
      positionName = text(person, "position");
    }
    return new PersonDetail(positionName,
        position == null ? "" : text(position, "id"), text(person, "status"));
  }

  private HttpRequest.Builder requestBuilder(String baseUrl, String path) {
    return HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + path))
        .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
        .header("callSysCode", properties.getCallSysCode())
        .header("Accept", "application/json");
  }

  private JsonNode execute(HttpRequest request, String operation) {
    for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
      try {
        HttpResponse<String> response = http.send(
            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 200) {
          JsonNode result = json.readTree(response.body());
          if (result != null && result.isObject()) {
            return result;
          }
          throw new OaPersonDirectoryException(operation + " 返回的 JSON 格式不正确");
        }
        if (!RETRYABLE_STATUS.contains(response.statusCode())
            || attempt == properties.getMaxAttempts()) {
          throw new OaPersonDirectoryException(operation + " HTTP " + response.statusCode());
        }
      } catch (IOException exception) {
        if (attempt == properties.getMaxAttempts()) {
          throw new OaPersonDirectoryException(operation + " 网络异常或返回非 JSON", exception);
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new OaPersonDirectoryException(operation + " 被中断", exception);
      }
      sleepBeforeRetry(attempt, operation);
    }
    throw new OaPersonDirectoryException(operation + " 请求失败");
  }

  private void sleepBeforeRetry(int attempt, String operation) {
    try {
      Thread.sleep(500L * attempt);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new OaPersonDirectoryException(operation + " 重试等待被中断", exception);
    }
  }

  private void requireTopLevelSuccess(JsonNode result, String operation) {
    if (!"0".equals(text(result, "errcode"))) {
      throw new OaPersonDirectoryException(operation + " 返回失败状态");
    }
  }

  private void requireMessageSuccess(JsonNode result, String operation) {
    if (!"0".equals(text(result.path("message"), "errcode"))) {
      throw new OaPersonDirectoryException(operation + " 返回失败状态");
    }
  }

  private static boolean startsWith(List<String> path, List<String> prefix) {
    return path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix);
  }

  private static boolean endsWith(List<String> path, List<String> suffix) {
    return path.size() >= suffix.size()
        && path.subList(path.size() - suffix.size(), path.size()).equals(suffix);
  }

  private static List<String> stringValues(JsonNode value) {
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (value.isArray()) {
      List<String> values = new ArrayList<>();
      value.forEach(item -> values.add(item.asText("")));
      return values;
    }
    return List.of(value.asText(""));
  }

  private static String text(JsonNode node, String field) {
    if (node == null || node.isNull()) {
      return "";
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asText("").trim();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record Page(int total, List<JsonNode> rows) {}
  private record ResolvedTarget(String id, List<String> path) {}
  private record TargetResolution(
      Map<String, ResolvedTarget> resolved, List<String> missing) {}
  private record PersonKey(String employeeNo, String personId) {}
  private record PersonDetail(String positionName, String positionId, String employmentStatus) {}

  private static final class MutablePerson {
    private final String oaUserId;
    private final String employeeNo;
    private final String name;
    private final Set<String> targetDepartmentPaths = new LinkedHashSet<>();
    private final Set<String> actualDepartmentPaths = new LinkedHashSet<>();
    private final Set<String> oaDepartmentIds = new LinkedHashSet<>();
    private final Set<String> targetDepartmentIds = new LinkedHashSet<>();
    private final Set<String> matchTypes = new LinkedHashSet<>();
    private String positionName = "";
    private String positionId = "";
    private String employmentStatus = "";

    private MutablePerson(String oaUserId, String employeeNo, String name) {
      this.oaUserId = oaUserId;
      this.employeeNo = employeeNo;
      this.name = name;
    }

    private OaDirectoryPerson toDirectoryPerson() {
      return new OaDirectoryPerson(
          oaUserId, employeeNo, name, positionName, positionId, employmentStatus,
          sorted(targetDepartmentPaths), sorted(actualDepartmentPaths), sorted(oaDepartmentIds),
          sorted(targetDepartmentIds), sorted(matchTypes));
    }

    private static List<String> sorted(Collection<String> values) {
      return values.stream().sorted().toList();
    }
  }
}
