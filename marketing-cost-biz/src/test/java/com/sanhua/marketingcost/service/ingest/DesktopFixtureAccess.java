package com.sanhua.marketingcost.service.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 可选桌面测试样本的可读性检查。
 *
 * <p>macOS 隐私权限或受限 CI 环境可能出现“文件存在但 Java 进程无法读取”的情况。
 * 真实样本测试本身属于可选 smoke test，此时应跳过，而不是把环境权限误判为业务回归失败。
 */
final class DesktopFixtureAccess {

  private DesktopFixtureAccess() {
  }

  static boolean isReadable(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      return false;
    }
    try (InputStream input = Files.newInputStream(path)) {
      input.read();
      return true;
    } catch (IOException | SecurityException ex) {
      return false;
    }
  }
}
