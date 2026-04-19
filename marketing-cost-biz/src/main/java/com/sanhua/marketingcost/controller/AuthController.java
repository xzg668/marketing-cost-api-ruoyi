package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.LoginRequest;
import com.sanhua.marketingcost.dto.LoginResponse;
import com.sanhua.marketingcost.entity.SysMenu;
import com.sanhua.marketingcost.entity.SysRole;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.entity.system.SysLoginLog;
import com.sanhua.marketingcost.mapper.SysLoginLogMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.JwtUtils;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.vo.RouterVO;
import com.sanhua.marketingcost.vo.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 认证控制器。
 * <p>
 * v1.3 改造：
 * <ul>
 *   <li>/login — 请求体 businessUnitType 必填（所有角色含 admin 均必选），Token 中携带该字段</li>
 *   <li>/me — 返回 roles、permissions、businessUnitType、userId、nickName</li>
 *   <li>/routers — 新增，按角色 + 业务单元过滤菜单并组装为树返回</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** admin 角色编码 */
    private static final String ADMIN_ROLE_KEY = "ADMIN";
    /** admin 权限通配符 */
    private static final String ALL_PERMISSION = "*:*:*";

    /** 登录日志状态：0 成功 */
    private static final String LOGIN_STATUS_SUCCESS = "0";
    /** 登录日志状态：1 失败 */
    private static final String LOGIN_STATUS_FAIL = "1";

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final SysUserService sysUserService;
    private final SysLoginLogMapper sysLoginLogMapper;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtUtils jwtUtils,
                          SysUserService sysUserService,
                          SysLoginLogMapper sysLoginLogMapper) {
        this.authenticationManager = authenticationManager;
        this.jwtUtils = jwtUtils;
        this.sysUserService = sysUserService;
        this.sysLoginLogMapper = sysLoginLogMapper;
    }

    /**
     * 登录：用户名 + 密码 + 业务单元三者必填；校验通过后签发携带 businessUnitType 的 JWT。
     * 无论成功或失败都会在 sys_login_log 中留下一条记录。
     */
    @PostMapping("/login")
    public CommonResult<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletRequest httpRequest) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            validateLoginBusinessUnit(userDetails.getUsername(), request.getBusinessUnitType());
            // v1.3：Token 中写入业务单元
            String token = jwtUtils.generateToken(userDetails.getUsername(), request.getBusinessUnitType());
            // 登录成功落库
            recordLoginLog(userDetails.getUsername(), LOGIN_STATUS_SUCCESS, "登录成功", httpRequest);
            return CommonResult.success(new LoginResponse(token, userDetails.getUsername()));
        } catch (RuntimeException ex) {
            // 登录失败（包括用户名不存在、密码错误、账号禁用等）
            recordLoginLog(request.getUsername(), LOGIN_STATUS_FAIL, ex.getMessage(), httpRequest);
            throw ex;
        }
    }

    /**
     * 非 admin 用户只能选择自身绑定的业务单元登录。
     * admin 作为跨业务单元管理入口，允许在登录页选择任一业务单元进入对应视角。
     */
    private void validateLoginBusinessUnit(String username, String requestedBusinessUnitType) {
        SysUser user = sysUserService.findByUsername(username);
        if (user == null) {
            return;
        }
        List<SysRole> roles = Optional.ofNullable(sysUserService.findRolesByUserId(user.getUserId()))
                .orElse(Collections.emptyList());
        boolean isAdmin = roles.stream()
                .anyMatch(r -> ADMIN_ROLE_KEY.equalsIgnoreCase(r.getRoleKey()));
        if (isAdmin) {
            return;
        }
        if (!StringUtils.hasText(user.getBusinessUnitType())) {
            throw new BadCredentialsException("账号未绑定业务单元，请联系管理员");
        }
        if (!user.getBusinessUnitType().equals(requestedBusinessUnitType)) {
            throw new BadCredentialsException("当前账号不属于所选业务单元");
        }
    }

    /**
     * 登出：记录一条退出日志。前端同时负责清除本地 Token。
     * 未登录调用也安全返回 — 只是 username 取为空。
     */
    @PostMapping("/logout")
    public CommonResult<Void> logout(Authentication authentication, HttpServletRequest httpRequest) {
        String username = "";
        if (authentication != null && authentication.getPrincipal() instanceof UserDetails ud) {
            username = ud.getUsername();
        }
        recordLoginLog(username, LOGIN_STATUS_SUCCESS, "退出成功", httpRequest);
        return CommonResult.success(null);
    }

    /**
     * 登录日志写入：失败不影响主流程。
     */
    void recordLoginLog(String username, String status, String msg, HttpServletRequest request) {
        try {
            SysLoginLog entry = new SysLoginLog();
            entry.setUserName(username == null ? "" : username);
            entry.setStatus(status);
            entry.setMsg(truncate(msg, 255));
            entry.setIpaddr(getClientIp(request));
            String ua = request == null ? null : request.getHeader("User-Agent");
            entry.setBrowser(parseBrowser(ua));
            entry.setOs(parseOs(ua));
            entry.setLoginTime(LocalDateTime.now());
            sysLoginLogMapper.insert(entry);
        } catch (Exception e) {
            // 日志落库失败不影响主流程（登录/登出）
            log.warn("登录日志写入失败: username={}, status={}, err={}", username, status, e.getMessage());
        }
    }

    /**
     * 取客户端 IP，考虑反向代理场景。
     */
    private String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 可能有多个 IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip == null ? "" : ip;
    }

    /**
     * 简单 User-Agent 浏览器识别（未引入专用库，按常见关键字匹配，足够业务使用）。
     */
    private String parseBrowser(String userAgent) {
        if (!StringUtils.hasText(userAgent)) {
            return "";
        }
        String ua = userAgent;
        // 顺序很关键：Edge 必须在 Chrome 之前（因 Edge 含 Chrome 字串）
        if (ua.contains("Edg/") || ua.contains("Edge/")) return "Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("Firefox")) return "Firefox";
        if (ua.contains("Chrome")) return "Chrome";
        if (ua.contains("Safari")) return "Safari";
        if (ua.contains("MSIE") || ua.contains("Trident")) return "IE";
        return "Other";
    }

    /**
     * 简单 User-Agent 操作系统识别。
     */
    private String parseOs(String userAgent) {
        if (!StringUtils.hasText(userAgent)) {
            return "";
        }
        String ua = userAgent;
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Mac OS") || ua.contains("Macintosh")) return "Mac OS";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iOS")) return "iOS";
        if (ua.contains("Linux")) return "Linux";
        return "Other";
    }

    /**
     * 文本截断到指定长度，避免超过字段上限导致插入失败。
     */
    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    /**
     * 获取当前登录用户信息，含角色、权限标识、业务单元。
     */
    @GetMapping("/me")
    public CommonResult<UserInfoVO> me(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        SysUser user = sysUserService.findByUsername(userDetails.getUsername());

        UserInfoVO vo = new UserInfoVO();
        vo.setUsername(userDetails.getUsername());
        if (user != null) {
            vo.setUserId(user.getUserId());
            vo.setNickName(user.getNickName());
            // 角色编码（去掉 ROLE_ 前缀，便于前端直接比较）
            List<SysRole> roles = sysUserService.findRolesByUserId(user.getUserId());
            vo.setRoles(roles.stream().map(SysRole::getRoleKey).collect(Collectors.toList()));
            // 权限标识
            vo.setPermissions(new ArrayList<>(sysUserService.findPermissionsByUserId(user.getUserId())));
        } else {
            vo.setRoles(Collections.emptyList());
            vo.setPermissions(Collections.emptyList());
        }
        vo.setBusinessUnitType(BusinessUnitContext.getCurrentBusinessUnitType());
        return CommonResult.success(vo);
    }

    /**
     * 获取当前用户可见的路由树。
     */
    @GetMapping("/routers")
    public CommonResult<List<RouterVO>> routers(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        SysUser user = sysUserService.findByUsername(userDetails.getUsername());
        if (user == null) {
            return CommonResult.success(Collections.emptyList());
        }
        String unit = BusinessUnitContext.getCurrentBusinessUnitType();
        List<SysMenu> menus = sysUserService.findVisibleMenus(user.getUserId(), unit);
        return CommonResult.success(buildRouterTree(menus));
    }

    /**
     * 将扁平菜单列表组装为路由树（parent_id = 0 为根节点）。
     * 包可见以便单元测试直接调用。
     */
    List<RouterVO> buildRouterTree(List<SysMenu> menus) {
        if (menus == null || menus.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, RouterVO> idToNode = new LinkedHashMap<>();
        // 先把全部节点建好
        for (SysMenu m : menus) {
            idToNode.put(m.getMenuId(), toRouterVO(m));
        }
        List<RouterVO> roots = new ArrayList<>();
        // 再挂接父子关系
        for (SysMenu m : menus) {
            RouterVO node = idToNode.get(m.getMenuId());
            Long pid = m.getParentId();
            if (pid == null || pid == 0L || !idToNode.containsKey(pid)) {
                roots.add(node);
            } else {
                RouterVO parent = idToNode.get(pid);
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    private RouterVO toRouterVO(SysMenu m) {
        RouterVO vo = new RouterVO();
        vo.setMenuId(m.getMenuId());
        vo.setName(m.getPath());
        vo.setPath(m.getPath());
        vo.setComponent(m.getComponent());
        RouterVO.Meta meta = new RouterVO.Meta();
        meta.setTitle(m.getMenuName());
        meta.setIcon(m.getIcon());
        // visible: "0"=显示, "1"=隐藏
        meta.setHidden("1".equals(m.getVisible()) ? Boolean.TRUE : null);
        vo.setMeta(meta);
        return vo;
    }
}
