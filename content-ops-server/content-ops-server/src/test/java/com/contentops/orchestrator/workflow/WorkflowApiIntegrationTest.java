package com.contentops.orchestrator.workflow;

import com.contentops.common.constant.AgentConstants;
import com.contentops.server.ContentOpsServerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端集成测试（mock 模式 + 选题后选平台的并行工作流）。
 *
 * <p>验证新流程：选题规划只跑一次 → 暂停等待用户选择平台 → 多平台扇出并行分支 /
 * 单平台直接继续；父工作流聚合为 COMPLETED，分支产物按平台聚合。
 */
@SpringBootTest(
        classes = ContentOpsServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "CONTENTOPS_MODE=mock",
                "spring.datasource.url=jdbc:h2:mem:itwf;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        }
)
class WorkflowApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private StringRedisTemplate redisTemplate;

    /** 内存桩 Redis 存储（进程内，测试自包含，不依赖外部 Redis）。 */
    private final Map<String, String> redisStore = new ConcurrentHashMap<>();

    @BeforeEach
    void stubRedis() {
        redisStore.clear();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> redisStore.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenAnswer(inv -> cursorOf(redisStore.keySet().stream()
                        .filter(k -> k.startsWith(AgentConstants.WORKFLOW_STATE_PREFIX))
                        .toList()));
        when(redisTemplate.delete(anyString()))
                .thenAnswer(inv -> redisStore.remove(inv.getArgument(0)) != null);
    }

    @Test
    @DisplayName("端到端：选题跑一次 → 用户选多平台 → 并行分支 → 父级聚合 COMPLETED")
    @SuppressWarnings("unchecked")
    void multiPlatformWorkflowRunsEndToEndInMockMode() throws InterruptedException {
        // 1. 启动工作流（平台仅为预选，选题后仍可调整）
        Map<String, Object> payload = Map.of(
                "accountProfile", Map.of(
                        "accountId", "it-001",
                        "accountName", "集成测试账号",
                        "niche", "个人成长",
                        "targetAudience", "职场人",
                        "tone", "专业亲和",
                        "platforms", List.of("公众号", "小红书")),
                "inputs", Map.of("topic", "AI 内容运营趋势", "additionalContext", "端到端集成测试"),
                "platformAccounts", Map.of(
                        "公众号", Map.of("accountId", "gh-001", "accountName", "干货分享站"),
                        "小红书", Map.of("accountId", "xhs-001", "accountName", "成长日记")),
                "requireHumanReview", false);

        ResponseEntity<Map> startResp = restTemplate.postForEntity("/api/v1/workflow/start", payload, Map.class);
        assertEquals(HttpStatus.OK, startResp.getStatusCode());
        Map<String, Object> startData = (Map<String, Object>) startResp.getBody().get("data");
        String workflowId = (String) startData.get("workflowId");
        assertNotNull(workflowId, "启动工作流应返回 workflowId");

        // 2. 等待选题规划完成并暂停（此时不产生分支）
        Map<String, Object> paused = waitForStatus(workflowId, "AWAITING_HUMAN", 60_000);
        assertEquals("topic-planning", paused.get("currentStage"),
                "选题完成后应先暂停在选题阶段等待用户选择平台");
        Map<String, Object> pausedInputs = (Map<String, Object>) paused.get("inputs");
        assertTrue(Boolean.TRUE.equals(pausedInputs.get("pauseForPlatformSelection")),
                "暂停状态应带 pauseForPlatformSelection 标记");
        assertFalse(pausedInputs.containsKey("branches"), "选题阶段不应已产生分支");

        // 3. 用户选择平台：与预选不同（改为公众号+抖音），验证选择可调整
        Map<String, Object> selectPayload = Map.of(
                "platforms", List.of("公众号", "抖音"),
                "platformAccounts", Map.of(
                        "公众号", Map.of("accountId", "gh-001", "accountName", "干货分享站"),
                        "抖音", Map.of("accountId", "dy-001", "accountName", "短视频日记")));
        ResponseEntity<Map> selectResp = restTemplate.postForEntity(
                "/api/v1/workflow/" + workflowId + "/select-platforms", selectPayload, Map.class);
        assertEquals(HttpStatus.OK, selectResp.getStatusCode());

        // 4. 轮询父工作流：等待中的分支逐条确认，直至聚合为 COMPLETED
        Map<String, Object> finalData = null;
        String status = "";
        int confirmations = 0;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> statusResp =
                    restTemplate.getForEntity("/api/v1/workflow/" + workflowId + "/status", Map.class);
            assertEquals(HttpStatus.OK, statusResp.getStatusCode());
            finalData = (Map<String, Object>) statusResp.getBody().get("data");
            status = String.valueOf(finalData.get("status"));
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                break;
            }
            if ("AWAITING_HUMAN".equals(status)) {
                for (Map<String, Object> branch : branchesOf(finalData)) {
                    if ("AWAITING_HUMAN".equals(branch.get("status"))) {
                        restTemplate.postForEntity(
                                "/api/v1/workflow/" + branch.get("workflowId") + "/confirm-substage",
                                Map.of("confirmedOutline", "一、引言\n二、正文\n三、结语"),
                                Map.class);
                        confirmations++;
                    }
                }
            }
            Thread.sleep(300);
        }

        assertEquals("COMPLETED", status,
                "父工作流应在超时前聚合为 COMPLETED；确认次数 " + confirmations
                        + "，失败信息：" + finalData.get("errorMessage"));
        assertTrue(confirmations >= 2, "两个平台分支应各自至少暂停一次等待人工确认");

        // 5. 父级产物包含共享选题 + 按平台聚合的分支产物
        Map<String, Object> artifacts = (Map<String, Object>) finalData.get("accumulatedArtifacts");
        assertTrue(artifacts.containsKey("topic-planning"), "选题规划产物应保留在父级");
        assertTrue(artifacts.containsKey("platform:wechat"), "父级应聚合微信公众号分支产物");
        assertTrue(artifacts.containsKey("platform:douyin"), "父级应聚合抖音分支产物");

        // 6. 分支共享同一选题产物，并使用该平台账号
        ResponseEntity<Map> branchResp = restTemplate.getForEntity(
                "/api/v1/workflow/" + workflowId + ":wechat/status", Map.class);
        assertEquals(HttpStatus.OK, branchResp.getStatusCode());
        Map<String, Object> branchData = (Map<String, Object>) branchResp.getBody().get("data");
        assertEquals("wechat", String.valueOf(
                ((Map<String, Object>) branchData.get("inputs")).get("platform")));
        assertEquals("干货分享站", ((Map<String, Object>) branchData.get("accountProfile")).get("accountName"));
        Map<String, Object> branchArtifacts = (Map<String, Object>) branchData.get("accumulatedArtifacts");
        assertTrue(branchArtifacts.containsKey("topic-planning"), "分支应共享父级选题产物");

        // 7. 列表接口返回分页契约，且分支记录不出现
        ResponseEntity<Map> listResp = restTemplate.getForEntity("/api/v1/workflow", Map.class);
        assertEquals(HttpStatus.OK, listResp.getStatusCode());
        Map<String, Object> listData = (Map<String, Object>) listResp.getBody().get("data");
        assertTrue(listData.containsKey("content"));
        assertTrue(listData.containsKey("page"));
        assertTrue(listData.containsKey("size"));
        assertTrue(listData.containsKey("total"));
        assertTrue(listData.containsKey("totalPages"));
        List<Object> content = (List<Object>) listData.get("content");
        long branchRows = content.stream()
                .map(row -> String.valueOf(((Map<String, Object>) row).get("workflowId")))
                .filter(id -> id.startsWith(workflowId + ":"))
                .count();
        assertEquals(0, branchRows, "分支工作流不应出现在列表中");
    }

    @Test
    @DisplayName("端到端：选题后选择单平台，无需多平台分支直接产出")
    @SuppressWarnings("unchecked")
    void singlePlatformWorkflowRunsEndToEndInMockMode() throws InterruptedException {
        Map<String, Object> payload = Map.of(
                "accountProfile", Map.of(
                        "accountId", "it-002",
                        "accountName", "单平台测试账号",
                        "niche", "个人成长",
                        "targetAudience", "职场人",
                        "tone", "专业亲和",
                        "platforms", List.of("小红书")),
                "inputs", Map.of("topic", "AI 内容运营趋势"),
                "requireHumanReview", false);

        ResponseEntity<Map> startResp = restTemplate.postForEntity("/api/v1/workflow/start", payload, Map.class);
        assertEquals(HttpStatus.OK, startResp.getStatusCode());
        String workflowId = (String) ((Map<String, Object>) startResp.getBody().get("data")).get("workflowId");

        waitForStatus(workflowId, "AWAITING_HUMAN", 60_000);
        ResponseEntity<Map> selectResp = restTemplate.postForEntity(
                "/api/v1/workflow/" + workflowId + "/select-platforms",
                Map.of("platforms", List.of("小红书")),
                Map.class);
        assertEquals(HttpStatus.OK, selectResp.getStatusCode());

        // 单平台：确认子阶段都发生在原工作流上，且不产生分支
        Map<String, Object> finalData = null;
        String status = "";
        int confirmations = 0;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> statusResp =
                    restTemplate.getForEntity("/api/v1/workflow/" + workflowId + "/status", Map.class);
            assertEquals(HttpStatus.OK, statusResp.getStatusCode());
            finalData = (Map<String, Object>) statusResp.getBody().get("data");
            status = String.valueOf(finalData.get("status"));
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                break;
            }
            if ("AWAITING_HUMAN".equals(status)) {
                restTemplate.postForEntity(
                        "/api/v1/workflow/" + workflowId + "/confirm-substage",
                        Map.of("confirmedOutline", "一、引言\n二、正文\n三、结语"),
                        Map.class);
                confirmations++;
            }
            Thread.sleep(300);
        }

        assertEquals("COMPLETED", status,
                "单平台工作流应在超时前完成；确认次数 " + confirmations
                        + "，失败信息：" + finalData.get("errorMessage"));
        assertTrue(confirmations >= 2, "单平台也应走渐进式生成等待人工确认");
        assertFalse(((Map<String, Object>) finalData.get("inputs")).containsKey("branches"),
                "单平台工作流不应产生分支");
        assertEquals("xiaohongshu", String.valueOf(
                ((Map<String, Object>) finalData.get("inputs")).get("platform")));
    }

    @Test
    @DisplayName("流水线阶段定义：返回 4 个主阶段（分析与优化为独立服务）")
    @SuppressWarnings("unchecked")
    void stagesAreExposed() {
        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/v1/workflow/stages", Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<Object> stages = (List<Object>) resp.getBody().get("data");
        assertEquals(4, stages.size(), "主流水线收敛为 4 个阶段：选题→内容→配图→发布");
        assertEquals("topic-planning", ((Map<String, Object>) stages.get(0)).get("code"));
        assertEquals("publishing", ((Map<String, Object>) stages.get(3)).get("code"));

        // 独立服务：数据分析和优化迭代单独暴露
        ResponseEntity<Map> servicesResp =
                restTemplate.getForEntity("/api/v1/workflow/standalone-services", Map.class);
        assertEquals(HttpStatus.OK, servicesResp.getStatusCode());
        List<Object> services = (List<Object>) servicesResp.getBody().get("data");
        assertEquals(2, services.size(), "数据分析、优化迭代作为独立服务");
    }

    /** 轮询直到工作流达到指定状态或超时。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> waitForStatus(String workflowId, String target, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> statusResp =
                    restTemplate.getForEntity("/api/v1/workflow/" + workflowId + "/status", Map.class);
            assertEquals(HttpStatus.OK, statusResp.getStatusCode());
            Map<String, Object> data = (Map<String, Object>) statusResp.getBody().get("data");
            if (target.equals(data.get("status"))) {
                return data;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("等待状态超时: " + target);
    }

    /** 从父工作流状态数据中提取分支元数据列表。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> branchesOf(Map<String, Object> statusData) {
        Map<String, Object> inputs = (Map<String, Object>) statusData.get("inputs");
        if (inputs == null || !(inputs.get("branches") instanceof List<?> branches)) {
            return List.of();
        }
        return (List<Map<String, Object>>) branches;
    }

    /** 简单游标：遍历给定 key 快照。 */
    private static Cursor<String> cursorOf(List<String> keys) {
        Iterator<String> iterator = new ArrayList<>(keys).iterator();
        return new Cursor<>() {
            @Override
            public CursorId getId() {
                return null;
            }

            @Override
            public long getCursorId() {
                return 0;
            }

            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public long getPosition() {
                return 0;
            }

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public String next() {
                return iterator.next();
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }
}
