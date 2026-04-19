package com.sanhua.marketingcost.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V5__ruoyi_init_data.sql 初始化脚本静态校验测试。
 * <p>
 * 由于本地无 MySQL/H2 环境（见 CLAUDE.md：测试不依赖活动数据库），
 * 采用正则解析 SQL 文本的方式静态校验：
 * <ul>
 *   <li>角色数量、role_key / role_name 正确</li>
 *   <li>菜单总数、目录/菜单/按钮数量正确</li>
 *   <li>菜单 parent_id 引用完整性（无悬空）</li>
 *   <li>同一 parent_id 下 path 唯一</li>
 *   <li>sys_role_menu 引用的 menu_id 全部存在</li>
 *   <li>admin / BU_DIRECTOR / BU_STAFF / OA_COLLABORATOR 的菜单关联数量符合设计</li>
 *   <li>业务单元字典数据正确</li>
 * </ul>
 * v1.3 菜单结构与前端 src/menu.js 1:1 对齐，总菜单数 50。
 */
class V5InitDataSqlTest {

    private static String sql;

    @BeforeAll
    static void loadSql() throws Exception {
        Path p = Paths.get("src/main/resources/db/V5__ruoyi_init_data.sql");
        if (!Files.exists(p)) {
            // 兼容 IDE 运行目录差异
            p = Paths.get("marketing-cost-biz/src/main/resources/db/V5__ruoyi_init_data.sql");
        }
        assertTrue(Files.exists(p), "V5 SQL 文件不存在: " + p.toAbsolutePath());
        sql = Files.readString(p);
    }

    // ========== 角色 ==========

    /** admin 角色 role_name 应为"系统管理员"（v1.3 方案 C 修订，非"超级管理员"） */
    @Test
    void adminRoleRenamedToSystemAdmin() {
        assertTrue(sql.contains("role_name   = '系统管理员'"),
                "admin 的 role_name 应为'系统管理员'（v1.3 方案 C）");
        assertFalse(sql.contains("'超级管理员'"),
                "不应再出现'超级管理员'字样");
    }

    /** 必须插入 3 个新角色：bu_director / bu_staff / oa_collaborator */
    @Test
    void threeNewRolesInserted() {
        assertTrue(sql.contains("'bu_director'"));
        assertTrue(sql.contains("'bu_staff'"));
        assertTrue(sql.contains("'oa_collaborator'"));
    }

    /** admin 的 data_scope 改为 2（按业务单元隔离），不再是 1 全部 */
    @Test
    void adminDataScopeIsBusinessUnit() {
        assertTrue(sql.contains("data_scope  = '2'"),
                "v1.3 方案 C：admin 的 data_scope 应为 2（按业务单元隔离）");
    }

    // ========== 菜单 ==========

    /** 菜单总数应为 50（v1.3 与 menu.js 1:1 对齐） */
    @Test
    void menuTotalCountIs50() {
        MenuStats stats = parseMenus();
        assertEquals(50, stats.all.size(),
                "菜单总数应为 50（9 目录 + 37 菜单 + 4 按钮）");
    }

    /** 目录数 9：系统管理/数据接入/基础数据/辅料管理(二级)/价格源管理/联动价(二级)/成本核算/数据分析/结账 */
    @Test
    void directoryCountIs9() {
        MenuStats stats = parseMenus();
        assertEquals(9, stats.byType("M"),
                "目录（menu_type='M'）应为 9 个");
    }

    /** 菜单数 37 */
    @Test
    void menuItemCountIs37() {
        MenuStats stats = parseMenus();
        assertEquals(37, stats.byType("C"),
                "菜单项（menu_type='C'）应为 37 个");
    }

    /** 按钮数 4（用户管理 4 个 CRUD 按钮） */
    @Test
    void buttonCountIs4() {
        MenuStats stats = parseMenus();
        assertEquals(4, stats.byType("F"),
                "按钮（menu_type='F'）应为 4 个");
    }

    /** parent_id 引用完整性：非 0 的 parent_id 必须对应某个 menu_id */
    @Test
    void parentIdReferencesAllExist() {
        MenuStats stats = parseMenus();
        Set<Integer> ids = stats.all.keySet();
        for (Map.Entry<Integer, Menu> e : stats.all.entrySet()) {
            int pid = e.getValue().parentId;
            if (pid == 0) continue;
            assertTrue(ids.contains(pid),
                    "menu_id=" + e.getKey() + " 的 parent_id=" + pid + " 不存在于菜单列表");
        }
    }

    /** 同一 parent_id 下 path 必须唯一（防止路由冲突） */
    @Test
    void pathUniquePerParent() {
        MenuStats stats = parseMenus();
        Map<String, Integer> seen = new HashMap<>();
        for (Menu m : stats.all.values()) {
            if (m.menuType.equals("F")) continue; // 按钮 path 为空，跳过
            String key = m.parentId + "|" + m.path;
            Integer prev = seen.put(key, m.menuId);
            assertNull(prev,
                    "同 parent_id=" + m.parentId + " 下 path='" + m.path
                            + "' 重复：menu_id " + prev + " 与 " + m.menuId);
        }
    }

    /** 核心顶级目录必须存在（与 menu.js 对齐） */
    @Test
    void topLevelDirectoriesAligned() {
        MenuStats stats = parseMenus();
        assertMenu(stats, 100, "系统管理", 0, "M");
        assertMenu(stats, 200, "数据接入", 0, "M");
        assertMenu(stats, 300, "基础数据", 0, "M");
        assertMenu(stats, 400, "价格源管理", 0, "M");
        assertMenu(stats, 500, "成本核算", 0, "M");
        assertMenu(stats, 600, "数据分析", 0, "M");
        assertMenu(stats, 700, "结账", 0, "M");
    }

    /** 辅料管理和联动价是二级目录 */
    @Test
    void secondLevelDirectoriesExist() {
        MenuStats stats = parseMenus();
        assertMenu(stats, 305, "辅料管理", 300, "M");
        assertMenu(stats, 401, "联动价", 400, "M");
    }

    /** v1.3 当前方案所有菜单 business_unit_type=NULL（未做商/家用区分） */
    @Test
    void allMenusBusinessUnitTypeNull() {
        // SQL 末尾字段为 business_unit_type，值为 NULL（无引号），不存在 'COMMERCIAL' / 'HOUSEHOLD' 菜单
        assertFalse(sql.contains(", 'COMMERCIAL')") || sql.contains(", 'HOUSEHOLD')"),
                "v1.3 菜单未区分商/家用，business_unit_type 应全部为 NULL");
    }

    // ========== 角色菜单关联 ==========

    /** admin(role_id=1) 关联全部 50 条菜单（SELECT 1, menu_id FROM sys_menu） */
    @Test
    void adminRoleMenuCoversAll() {
        assertTrue(sql.contains("SELECT 1, menu_id FROM sys_menu"),
                "admin 应关联全部菜单");
    }

    /** BU_STAFF(role_id=11) 只关联业务菜单（menu_id >= 200） */
    @Test
    void buStaffOnlyBusinessMenus() {
        assertTrue(sql.contains("SELECT 11, menu_id FROM sys_menu")
                        && sql.contains("WHERE menu_id >= 200"),
                "BU_STAFF 应只关联 menu_id >= 200 的业务菜单");
    }

    /** BU_DIRECTOR(role_id=10) 仅关联用户管理 + 全部业务菜单 */
    @Test
    void buDirectorCoversSystemSubsetAndAllBusiness() {
        Pattern p = Pattern.compile(
                "SELECT 10, menu_id FROM sys_menu[\\s\\S]{0,600}menu_id >= 200",
                Pattern.MULTILINE);
        assertTrue(p.matcher(sql).find(),
                "BU_DIRECTOR 应关联系统管理子集 IN(...) + OR menu_id >= 200");
        // 必须包含用户管理 + 4 个用户按钮，不包含角色管理
        String block = extractBuDirectorBlock();
        for (int id : new int[]{100, 101, 1011, 1012, 1013, 1014}) {
            assertTrue(block.contains(String.valueOf(id)),
                    "BU_DIRECTOR 关联应包含 menu_id=" + id);
        }
        assertFalse(block.contains("102"), "BU_DIRECTOR 不应包含角色管理 menu_id=102");
    }

    // ========== 字典 ==========

    /** 业务单元字典类型 + 两个字典数据（COMMERCIAL/HOUSEHOLD） */
    @Test
    void businessUnitDictInitialized() {
        assertTrue(sql.contains("'biz_unit_type'"));
        assertTrue(sql.contains("'COMMERCIAL'"));
        assertTrue(sql.contains("'HOUSEHOLD'"));
    }

    // ========== 幂等性 ==========

    /** 写入类语句应为 INSERT IGNORE（保证重复执行幂等） */
    @Test
    void allInsertsAreIdempotent() {
        // 扫描所有 INSERT INTO，不应有裸的 INSERT INTO（除非是 INSERT IGNORE）
        Pattern bare = Pattern.compile("(?<!IGNORE )INSERT\\s+INTO\\s+sys_",
                Pattern.CASE_INSENSITIVE);
        Matcher m = bare.matcher(sql);
        List<String> offenders = new ArrayList<>();
        while (m.find()) {
            offenders.add(sql.substring(m.start(), Math.min(m.end() + 20, sql.length())));
        }
        assertTrue(offenders.isEmpty(),
                "所有 INSERT 应使用 INSERT IGNORE 保证幂等，但发现: " + offenders);
    }

    // ========== 辅助 ==========

    private void assertMenu(MenuStats s, int id, String name, int parentId, String type) {
        Menu m = s.all.get(id);
        assertNotNull(m, "menu_id=" + id + " 不存在");
        assertEquals(name, m.menuName, "menu_id=" + id + " 名称不匹配");
        assertEquals(parentId, m.parentId, "menu_id=" + id + " parent_id 不匹配");
        assertEquals(type, m.menuType, "menu_id=" + id + " menu_type 不匹配");
    }

    private String extractBuDirectorBlock() {
        int start = sql.indexOf("SELECT 10, menu_id FROM sys_menu");
        int end = sql.indexOf(";", start);
        assertTrue(start > 0 && end > start);
        return sql.substring(start, end);
    }

    /** 解析 sys_menu 的 INSERT VALUES 行 */
    private MenuStats parseMenus() {
        MenuStats stats = new MenuStats();
        // 匹配每一个 VALUES 行：(menu_id, 'menu_name', parent_id, order_num, 'path', ...)
        // 关键字段按位置：1=menu_id, 2=menu_name, 3=parent_id, 4=order_num, 5=path, 6=component, 7=menu_type
        Pattern row = Pattern.compile(
                "\\(\\s*(\\d+)\\s*,\\s*'([^']+)'\\s*,\\s*(\\d+)\\s*,\\s*\\d+\\s*,\\s*'([^']*)'\\s*,\\s*(?:NULL|'[^']*')\\s*,\\s*'([MCF])'");
        Matcher m = row.matcher(sql);
        while (m.find()) {
            Menu menu = new Menu();
            menu.menuId = Integer.parseInt(m.group(1));
            menu.menuName = m.group(2);
            menu.parentId = Integer.parseInt(m.group(3));
            menu.path = m.group(4);
            menu.menuType = m.group(5);
            stats.all.put(menu.menuId, menu);
        }
        return stats;
    }

    private static class Menu {
        int menuId;
        String menuName;
        int parentId;
        String path;
        String menuType;
    }

    private static class MenuStats {
        final Map<Integer, Menu> all = new LinkedHashMap<>();

        int byType(String type) {
            return (int) all.values().stream().filter(x -> x.menuType.equals(type)).count();
        }
    }
}
