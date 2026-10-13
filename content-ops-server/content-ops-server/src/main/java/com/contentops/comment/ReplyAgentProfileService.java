package com.contentops.comment;

import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 场景客服回复 Agent 配置服务：解析当前应使用的场景、维护配置、内置种子。
 *
 * <p>解析顺序：显式 profileId → 该租户默认 profile → 全局默认（电商带货）。
 * 因此「不配置也能用」（电商带货兜底），要切课程客服只需新增/启用一条 COURSE profile 并激活。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyAgentProfileService implements ApplicationRunner {

    public static final String DEFAULT_PROFILE_ID = "reply-agent-ecommerce-default";

    private final ReplyAgentProfileRepository repository;

    // ──────────────── 解析 ────────────────

    /** 解析要使用的场景配置（显式优先，其次默认，最后兜底电商带货）。 */
    public ReplyAgentProfile resolve(String ownerId, String profileId) {
        if (profileId != null && !profileId.isBlank()) {
            return repository.findById(profileId.trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "回复 Agent 配置不存在: " + profileId));
        }
        Optional<ReplyAgentProfile> ownerDefault = repository.findDefault(ownerId, null);
        if (ownerDefault.isPresent()) {
            return ownerDefault.get();
        }
        Optional<ReplyAgentProfile> globalDefault = repository.findDefault(null, null);
        if (globalDefault.isPresent()) {
            return globalDefault.get();
        }
        return repository.findById(DEFAULT_PROFILE_ID)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE,
                        "未配置任何回复 Agent（可 POST /api/v1/comments/reply-agents 新增，或重启以写入内置场景）"));
    }

    /** 解析（带场景偏好）：先按场景找默认，找不到再用通用默认。 */
    public ReplyAgentProfile resolveByScenario(String ownerId, String scenario) {
        return repository.findDefault(ownerId, scenario)
                .or(() -> repository.findDefault(ownerId, null))
                .or(() -> repository.findDefault(null, scenario))
                .orElseGet(() -> resolve(ownerId, null));
    }

    public List<ReplyAgentProfile> list(String ownerId) {
        return repository.list(ownerId, 100);
    }

    // ──────────────── 维护 ────────────────

    public ReplyAgentProfile create(String ownerId, ReplyAgentProfile request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "name 不能为空");
        }
        ReplyAgentProfile profile = ReplyAgentProfile.builder()
                .profileId(UUID.randomUUID().toString())
                .ownerId(ownerId)
                .name(request.getName().trim())
                .scenario(request.getScenario() == null || request.getScenario().isBlank()
                        ? "GENERIC" : request.getScenario().trim().toUpperCase())
                .description(request.getDescription())
                .tone(request.getTone())
                .systemPrompt(request.getSystemPrompt())
                .productFacts(request.getProductFacts())
                .forbiddenClaims(request.getForbiddenClaims())
                .escalateKeywords(request.getEscalateKeywords())
                .replyMaxChars(request.getReplyMaxChars() == null ? 120 : request.getReplyMaxChars())
                .allowLinks(request.isAllowLinks())
                .enabled(true)
                .defaultProfile(false)
                .build();
        repository.insert(profile);
        log.info("[ReplyAgent] 新增场景配置: id={}, name={}, scenario={}",
                profile.getProfileId(), profile.getName(), profile.getScenario());
        return profile;
    }

    public ReplyAgentProfile update(String ownerId, String profileId, ReplyAgentProfile request) {
        ReplyAgentProfile profile = requireProfile(profileId);
        if (request.getName() != null && !request.getName().isBlank()) {
            profile.setName(request.getName().trim());
        }
        if (request.getScenario() != null && !request.getScenario().isBlank()) {
            profile.setScenario(request.getScenario().trim().toUpperCase());
        }
        if (request.getDescription() != null) {
            profile.setDescription(request.getDescription());
        }
        if (request.getTone() != null) {
            profile.setTone(request.getTone());
        }
        if (request.getSystemPrompt() != null) {
            profile.setSystemPrompt(request.getSystemPrompt());
        }
        if (request.getProductFacts() != null) {
            profile.setProductFacts(request.getProductFacts());
        }
        if (request.getForbiddenClaims() != null) {
            profile.setForbiddenClaims(request.getForbiddenClaims());
        }
        if (request.getEscalateKeywords() != null) {
            profile.setEscalateKeywords(request.getEscalateKeywords());
        }
        if (request.getReplyMaxChars() != null) {
            profile.setReplyMaxChars(request.getReplyMaxChars());
        }
        profile.setAllowLinks(request.isAllowLinks());
        repository.update(profile);
        return repository.findById(profileId).orElse(profile);
    }

    public void delete(String profileId) {
        repository.delete(profileId);
    }

    /** 激活为默认场景（租户内唯一）。 */
    public ReplyAgentProfile activate(String ownerId, String profileId) {
        ReplyAgentProfile profile = requireProfile(profileId);
        repository.clearDefault(ownerId);
        profile.setDefaultProfile(true);
        profile.setEnabled(true);
        repository.update(profile);
        log.info("[ReplyAgent] 已激活默认场景: id={}, name={}", profileId, profile.getName());
        return repository.findById(profileId).orElse(profile);
    }

    public void setEnabled(String profileId, boolean enabled) {
        ReplyAgentProfile profile = requireProfile(profileId);
        profile.setEnabled(enabled);
        repository.update(profile);
    }

    private ReplyAgentProfile requireProfile(String profileId) {
        return repository.findById(profileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "回复 Agent 配置不存在: " + profileId));
    }

    // ──────────────── 内置场景种子 ────────────────

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (repository.count() > 0) {
                return;
            }
            repository.insert(ReplyAgentProfile.builder()
                    .profileId(DEFAULT_PROFILE_ID)
                    .name("电商带货客服")
                    .scenario("ECOMMERCE")
                    .description("带货商品评论区的客服回复：解答规格/发货/优惠/售后，适度引导下单")
                    .tone("热情但不夸张，口语化，先解决问题再引导下单")
                    .systemPrompt("你是一位电商带货客服，负责解答商品规格、发货时效、优惠与售后政策，"
                            + "并适度引导下单。不要夸大功效，不要承诺事实里没有的信息。")
                    .productFacts("（示例）商品：待补充；价格：待补充；优惠：待补充；发货：48 小时内发货；"
                            + "售后：7 天无理由退换（不影响二次销售）")
                    .forbiddenClaims("绝对正品,保证见效,全网最低价,假一赔十")
                    .escalateKeywords("退款,退货,投诉,发票,差价,质量问题,律师,维权")
                    .replyMaxChars(120)
                    .allowLinks(false)
                    .enabled(true)
                    .defaultProfile(true)
                    .build());

            repository.insert(ReplyAgentProfile.builder()
                    .profileId("reply-agent-generic-default")
                    .name("通用客服")
                    .scenario("GENERIC")
                    .description("不区分场景的通用客服回复兜底")
                    .tone("自然、真诚、口语化")
                    .systemPrompt("你是一位专业客服，负责解答用户疑问并给出可执行建议。")
                    .productFacts("")
                    .forbiddenClaims("绝对,保证见效")
                    .escalateKeywords("退款,投诉,律师")
                    .replyMaxChars(120)
                    .allowLinks(false)
                    .enabled(true)
                    .defaultProfile(false)
                    .build());

            // 课程客服：作为「定制新场景」的示例模板预置，默认关闭，需要时改配置或点激活即可
            repository.insert(ReplyAgentProfile.builder()
                    .profileId("reply-agent-course-template")
                    .name("课程客服（示例模板，默认停用）")
                    .scenario("COURSE")
                    .description("卖课/训练营场景：解答课程内容、适合人群、学习方式与购买流程")
                    .tone("专业、克制、有耐心，不夸大效果，不承诺收益")
                    .systemPrompt("你是一位课程顾问客服，负责解答课程内容、适合人群、学习方式与购买流程。"
                            + "不承诺学习效果与收益，涉及退费与合同一律引导人工。")
                    .productFacts("（示例）课程：待补充；价格：待补充；课时：待补充；形式：视频+作业点评；"
                            + "有效期：待补充")
                    .forbiddenClaims("保证涨薪,保证过审,包就业,保过")
                    .escalateKeywords("退费,退款,发票,合同,投诉,律师")
                    .replyMaxChars(140)
                    .allowLinks(false)
                    .enabled(false)
                    .defaultProfile(false)
                    .build());

            log.info("[ReplyAgent] 内置场景已写入: 电商带货客服(默认) / 通用客服 / 课程客服(模板,停用)");
        } catch (Exception e) {
            log.warn("[ReplyAgent] 内置场景写入失败（忽略）: {}", e.getMessage());
        }
    }
}