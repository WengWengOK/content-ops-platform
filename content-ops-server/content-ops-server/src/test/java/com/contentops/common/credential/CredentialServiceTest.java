package com.contentops.common.credential;

import com.contentops.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭据服务测试：多租户隔离、默认凭据切换、掩码与解析优先级。
 */
class CredentialServiceTest {

    private PlatformCredentialRepository repository;
    private CredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformCredentialRepository.class);
        service = new CredentialService(repository, new CredentialCipher("unit-test-key"), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("新增凭据：令牌加密落库，返回视图只含掩码")
    void createEncryptsAndMasks() {
        CredentialService.CredentialView view = service.create("owner-1",
                new CredentialService.CreateRequest("xiaohongshu", "主号", "xhs-001", "xhs-mcp",
                        "http://127.0.0.1:18060", "token-abcd1234", true, true));

        assertThat(view.accessTokenMasked()).isEqualTo("****1234");
        assertThat(view.baseUrl()).isEqualTo("http://127.0.0.1:18060");
        assertThat(view.defaultCredential()).isTrue();
        verify(repository).clearDefault("xiaohongshu", "owner-1", view.credentialId());
    }

    @Test
    @DisplayName("解析凭据：显式 ID 优先，且必须属于当前租户（跨租户 403）")
    void resolveRejectsCrossTenant() {
        when(repository.findById("cred-other")).thenReturn(Optional.of(PlatformCredential.builder()
                .credentialId("cred-other").ownerId("owner-2").platform("xiaohongshu")
                .enabled(true).accessToken("enc:v1:xxx").baseUrl("http://x").build()));

        BusinessException ex = catchThrowableOfType(
                () -> service.resolve("owner-1", "xiaohongshu", "cred-other"), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("无权访问其它租户的凭据");
    }

    @Test
    @DisplayName("解析凭据：未指定时取本租户默认凭据")
    void resolveUsesOwnerDefault() {
        when(repository.findDefault("xiaohongshu", "owner-1")).thenReturn(Optional.of(PlatformCredential.builder()
                .credentialId("cred-1").ownerId("owner-1").platform("xiaohongshu")
                .enabled(true).accessToken("plain-1").baseUrl("http://127.0.0.1:18060").build()));

        Optional<PlatformCredential> resolved = service.resolve("owner-1", "xiaohongshu", null);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getCredentialId()).isEqualTo("cred-1");
    }

    @Test
    @DisplayName("列出凭据：不返回其它租户的凭据")
    void listFiltersOtherTenants() {
        when(repository.list(anyString(), anyInt())).thenReturn(List.of(
                PlatformCredential.builder().credentialId("c1").ownerId("owner-1").platform("xiaohongshu")
                        .enabled(true).accessToken("t1").createdAt(LocalDateTime.now()).build(),
                PlatformCredential.builder().credentialId("c2").ownerId("owner-2").platform("xiaohongshu")
                        .enabled(true).accessToken("t2").createdAt(LocalDateTime.now()).build()));

        List<CredentialService.CredentialView> views = service.list("owner-1", false);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).credentialId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("更新凭据：accessToken 传空表示保持原值")
    void updateKeepsTokenWhenBlank() {
        PlatformCredential existing = PlatformCredential.builder()
                .credentialId("c1").ownerId("owner-1").platform("xiaohongshu")
                .accessToken("enc:v1:keep").baseUrl("http://old").enabled(true).build();
        when(repository.findById("c1")).thenReturn(Optional.of(existing));

        service.update("c1", "owner-1", new CredentialService.UpdateRequest(
                "改名", null, null, "http://new", "", null, null));

        verify(repository).update(any(PlatformCredential.class));
        assertThat(existing.getAccessToken()).isEqualTo("enc:v1:keep");
        assertThat(existing.getBaseUrl()).isEqualTo("http://new");
        assertThat(existing.getAccountName()).isEqualTo("改名");
    }

    @Test
    @DisplayName("删除凭据：跨租户禁止")
    void deleteRejectsCrossTenant() {
        when(repository.findById("c2")).thenReturn(Optional.of(PlatformCredential.builder()
                .credentialId("c2").ownerId("owner-2").platform("xiaohongshu").enabled(true).build()));

        catchThrowableOfType(() -> service.delete("c2", "owner-1"), BusinessException.class);

        verify(repository, never()).delete(eq("c2"));
    }
}