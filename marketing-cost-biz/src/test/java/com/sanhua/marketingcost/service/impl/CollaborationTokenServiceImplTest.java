package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.mapper.LpCollaborationTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 协作者 Token Service 单元测试
 */
class CollaborationTokenServiceImplTest {

    private LpCollaborationTokenMapper tokenMapper;
    private CollaborationTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        tokenMapper = mock(LpCollaborationTokenMapper.class);
        service = new CollaborationTokenServiceImpl(tokenMapper);
    }

    // ========== 生成 Token ==========

    @Test
    @DisplayName("生成 Token — 返回有效记录")
    void generateToken_returnsValidRecord() {
        when(tokenMapper.insert(any(LpCollaborationToken.class))).thenReturn(1);

        LpCollaborationToken result = service.generateToken(1L, "bom-supplement", "OA-2026-001", 24);

        assertNotNull(result);
        assertNotNull(result.getToken());
        assertEquals(32, result.getToken().length()); // UUID 去掉连字符
        assertEquals(1L, result.getUserId());
        assertEquals("bom-supplement", result.getTokenType());
        assertEquals("OA-2026-001", result.getRemark());
        assertEquals("0", result.getStatus());
        assertNotNull(result.getExpireTime());
        assertTrue(result.getExpireTime().isAfter(LocalDateTime.now()));
        verify(tokenMapper).insert(any(LpCollaborationToken.class));
    }

    @Test
    @DisplayName("生成 Token — 每次生成不同的 Token 值")
    void generateToken_uniqueTokens() {
        when(tokenMapper.insert(any(LpCollaborationToken.class))).thenReturn(1);

        LpCollaborationToken t1 = service.generateToken(1L, "bom-supplement", "OA-001", 24);
        LpCollaborationToken t2 = service.generateToken(1L, "bom-supplement", "OA-001", 24);

        assertNotEquals(t1.getToken(), t2.getToken());
    }

    // ========== 校验 Token ==========

    @Test
    @DisplayName("校验 — Token 不存在返回 null")
    void validateToken_notFound_returnsNull() {
        when(tokenMapper.selectOne(any())).thenReturn(null);

        assertNull(service.validateToken("nonexistent"));
    }

    @Test
    @DisplayName("校验 — Token 已使用返回 null")
    void validateToken_used_returnsNull() {
        LpCollaborationToken record = new LpCollaborationToken();
        record.setTokenId(1L);
        record.setStatus("1"); // 已使用
        record.setExpireTime(LocalDateTime.now().plusHours(1));
        when(tokenMapper.selectOne(any())).thenReturn(record);

        assertNull(service.validateToken("used-token"));
    }

    @Test
    @DisplayName("校验 — Token 已过期返回 null 并更新状态")
    void validateToken_expired_returnsNullAndUpdatesStatus() {
        LpCollaborationToken record = new LpCollaborationToken();
        record.setTokenId(1L);
        record.setStatus("0"); // 状态仍为有效
        record.setExpireTime(LocalDateTime.now().minusHours(1)); // 但时间已过期
        when(tokenMapper.selectOne(any())).thenReturn(record);
        when(tokenMapper.updateById(any(LpCollaborationToken.class))).thenReturn(1);

        assertNull(service.validateToken("expired-token"));
        // 应自动更新状态为已过期
        verify(tokenMapper).updateById(any(LpCollaborationToken.class));
    }

    @Test
    @DisplayName("校验 — Token 状态为已过期直接返回 null")
    void validateToken_statusExpired_returnsNull() {
        LpCollaborationToken record = new LpCollaborationToken();
        record.setTokenId(1L);
        record.setStatus("2"); // 已过期
        record.setExpireTime(LocalDateTime.now().minusHours(1));
        when(tokenMapper.selectOne(any())).thenReturn(record);

        assertNull(service.validateToken("expired-status-token"));
        // 状态已经是过期，不需要再更新
        verify(tokenMapper, never()).updateById(any(LpCollaborationToken.class));
    }

    @Test
    @DisplayName("校验 — 有效 Token 返回记录")
    void validateToken_valid_returnsRecord() {
        LpCollaborationToken record = new LpCollaborationToken();
        record.setTokenId(1L);
        record.setToken("valid-token");
        record.setStatus("0"); // 有效
        record.setExpireTime(LocalDateTime.now().plusHours(10)); // 未过期
        record.setUserId(1L);
        record.setTokenType("bom-supplement");
        record.setRemark("OA-2026-001");
        when(tokenMapper.selectOne(any())).thenReturn(record);

        LpCollaborationToken result = service.validateToken("valid-token");

        assertNotNull(result);
        assertEquals(1L, result.getTokenId());
        assertEquals("bom-supplement", result.getTokenType());
    }

    // ========== 标记已使用 ==========

    @Test
    @DisplayName("标记已使用 — 更新状态为 1")
    void markUsed_updatesStatus() {
        when(tokenMapper.updateById(any(LpCollaborationToken.class))).thenReturn(1);

        service.markUsed(1L);

        verify(tokenMapper).updateById(argThat((LpCollaborationToken token) ->
                token.getTokenId().equals(1L) && "1".equals(token.getStatus())
        ));
    }
}
