package com.sanhua.marketingcost.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 若依可追溯性静态校验（T33）。
 * <p>
 * 四项硬性要求（设计文档 v1.3 规定的"继承若依"证据）：
 * <ol>
 *   <li>顶层 pom.xml 使用 {@code cn.iocoder.boot} 作为 groupId</li>
 *   <li>Java 代码至少有一处 {@code import cn.iocoder.yudao.framework.*}</li>
 *   <li>仓库根存在 {@code yudao-framework/} 目录</li>
 *   <li>菜单权限标识遵循若依规范 {@code module:resource:action}</li>
 * </ol>
 * 测试不依赖运行时环境，仅做文件系统 + 文本校验。
 */
class RuoYiTraceabilityTest {

    /** 定位仓库根目录（兼容 IDE 或 Maven 从不同工作目录执行） */
    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        // 从当前目录向上查找，直到看到 yudao-framework 或到达文件系统根
        Path p = cwd;
        while (p != null) {
            if (Files.isDirectory(p.resolve("yudao-framework"))) {
                return p;
            }
            p = p.getParent();
        }
        // 兜底：仍用 cwd；后续断言会给出清晰失败信息
        return cwd;
    }

    @Test
    @DisplayName("追溯性①—— 顶层 pom.xml 存在 cn.iocoder.boot groupId")
    void rootPomContainsIocoderGroupId() throws IOException {
        Path root = repoRoot();
        Path pom = root.resolve("pom.xml");
        assertTrue(Files.exists(pom), "顶层 pom.xml 应存在于 " + root);
        String content = Files.readString(pom);
        assertTrue(content.contains("cn.iocoder.boot"),
                "顶层 pom.xml 应引用 groupId=cn.iocoder.boot（若依 Yudao BOM）");
    }

    @Test
    @DisplayName("追溯性②—— Java 代码存在 import cn.iocoder.yudao.framework.*")
    void javaCodeImportsYudaoFramework() throws IOException {
        Path srcDir = repoRoot().resolve("marketing-cost-biz/src/main/java");
        assertTrue(Files.isDirectory(srcDir),
                "业务代码目录应存在: " + srcDir);
        Pattern importPattern = Pattern.compile(
                "import\\s+cn\\.iocoder\\.yudao\\.framework\\.[\\w.]+;");
        try (Stream<Path> stream = Files.walk(srcDir)) {
            long hits = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return importPattern.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .count();
            assertTrue(hits > 0,
                    "至少应有一个 .java 文件 import cn.iocoder.yudao.framework.*");
        }
    }

    @Test
    @DisplayName("追溯性③—— 存在 yudao-framework/ 目录且含子模块 pom")
    void yudaoFrameworkDirectoryExists() {
        Path dir = repoRoot().resolve("yudao-framework");
        assertTrue(Files.isDirectory(dir),
                "应存在 yudao-framework/ 目录: " + dir);
        Path modulePom = dir.resolve("pom.xml");
        assertTrue(Files.exists(modulePom),
                "yudao-framework 应为 Maven 子模块（含 pom.xml）");
    }

    @Test
    @DisplayName("追溯性④—— 菜单权限标识符合若依 module:resource:action 规范")
    void menuPermsFollowRuoYiFormat() throws IOException {
        Path sqlFile = repoRoot().resolve("marketing-cost-biz/src/main/resources/db/V5__ruoyi_init_data.sql");
        assertTrue(Files.exists(sqlFile), "V5 菜单种子 SQL 应存在: " + sqlFile);
        String sql = Files.readString(sqlFile);

        // 若依规范：三段式 x:y:z（如 system:user:list / ingest:oa-form:list）
        // 允许段内字符: 小写字母 / 数字 / 连字符
        Pattern ruoyiPerm = Pattern.compile("'([a-z0-9-]+:[a-z0-9-]+:[a-z0-9-]+)'");
        Matcher m = ruoyiPerm.matcher(sql);
        int count = 0;
        boolean hasSystemUserList = false;
        while (m.find()) {
            count++;
            if ("system:user:list".equals(m.group(1))) {
                hasSystemUserList = true;
            }
        }
        assertTrue(count >= 10,
                "V5 中若依格式权限标识至少应有 10 个，实际: " + count);
        assertTrue(hasSystemUserList,
                "应存在经典若依示例权限 system:user:list（任务文档要求）");

        // 反向：不应出现 ROLE_ADMIN 这种硬编码权限串混入 perms 列
        // （perms 列仅存若依三段式；角色前缀 ROLE_ 属于 Spring Security 授权体系）
        List<String> invalid = List.of("'ROLE_", "'admin:");
        for (String bad : invalid) {
            assertFalse(sql.contains("perms") && sql.contains(bad),
                    "perms 列不应出现非若依格式的权限串: " + bad);
        }
    }
}
