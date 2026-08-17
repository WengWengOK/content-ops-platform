package com.contentops.common.security;

import com.contentops.common.dto.AgentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 注册 / 登录接口（鉴权开关关闭时同样可用，便于提前开通账号）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String USERNAME_PATTERN = "^[A-Za-z0-9_]{3,32}$";

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;

    @PostMapping("/register")
    public AgentResponse<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        if (request.username() == null || !request.username().matches(USERNAME_PATTERN)) {
            return AgentResponse.failure("auth", "用户名需为 3-32 位字母/数字/下划线");
        }
        if (request.password() == null || request.password().length() < 8 || request.password().length() > 64) {
            return AgentResponse.failure("auth", "密码长度需为 8-64 位");
        }
        if (userRepository.findByUsername(request.username()).isPresent()) {
            return AgentResponse.failure("auth", "用户名已存在");
        }

        PasswordHasher.HashedPassword hashed = passwordHasher.hash(request.password());
        String userId = UUID.randomUUID().toString();
        if (!userRepository.createWithCacheEvict(userId, request.username(),
                hashed.hashBase64(), hashed.saltBase64())) {
            return AgentResponse.failure("auth", "注册失败，请稍后重试");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("username", request.username());
        data.put("role", UserRole.CREATOR.name());
        log.info("[Auth] 新用户注册: username={}", request.username());
        return AgentResponse.success("auth", data);
    }

    @PostMapping("/login")
    public AgentResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.password() == null) {
            return AgentResponse.failure("auth", "用户名和密码不能为空");
        }
        UserRepository.UserRecord user = userRepository.findByUsername(request.username())
                .orElse(null);
        if (user == null || !passwordHasher.verify(
                request.password(), user.passwordSalt(), user.passwordHash())) {
            return AgentResponse.failure("auth", "用户名或密码错误");
        }

        String token = jwtService.create(user.userId(), user.username(), user.role());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.userId());
        data.put("username", user.username());
        data.put("role", user.role());
        log.info("[Auth] 用户登录: username={}", user.username());
        return AgentResponse.success("auth", data);
    }

    @GetMapping("/me")
    public AgentResponse<Map<String, Object>> me() {
        var principal = AuthContext.current();
        Map<String, Object> data = new HashMap<>();
        data.put("userId", principal.map(AuthContext.AuthPrincipal::userId).orElse(null));
        data.put("username", principal.map(AuthContext.AuthPrincipal::username).orElse(null));
        data.put("role", principal.map(AuthContext.AuthPrincipal::role).orElse(null));
        return AgentResponse.success("auth", data);
    }

    @RequireRole(UserRole.ADMIN)
    @GetMapping("/users")
    public AgentResponse<Map<String, Object>> users() {
        return AgentResponse.success("auth", Map.of("total", userRepository.listAll().size(),
                "users", userRepository.listAll()));
    }

    @RequireRole(UserRole.ADMIN)
    @PutMapping("/users/{userId}/role")
    public AgentResponse<Map<String, Object>> updateRole(
            @PathVariable String userId,
            @RequestBody RoleUpdateRequest request) {
        if (request.role() == null || UserRole.valueOfSafe(request.role()) == null) {
            return AgentResponse.failure("auth", "角色无效，可选：ADMIN / CREATOR / VIEWER");
        }
        boolean updated = userRepository.updateRole(userId, request.role().toUpperCase());
        return updated
                ? AgentResponse.success("auth", Map.of("userId", userId, "role", request.role().toUpperCase()))
                : AgentResponse.failure("auth", "用户不存在");
    }

    public record RegisterRequest(String username, String password) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record RoleUpdateRequest(String role) {
    }
}
