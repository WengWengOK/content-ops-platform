package com.contentops.topic.agent;

import com.contentops.common.dto.TopicPlanResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.TokenStream;

/**
 * LangChain4j AI Service for multi-turn topic discussion.
 *
 * <p>Implements the "把TRAE当讨论对象" (use TRAE as discussion partner) pattern:
 * instead of treating the AI as a ghostwriter that produces a one-shot result,
 * the user has a conversation with the AI to explore and refine ideas.
 *
 * <p>The conversation flow:
 * <ol>
 *   <li><b>IDEATION</b> — user provides a fuzzy idea</li>
 *   <li><b>CLARIFICATION</b> — AI asks 2-3 clarifying questions to understand intent</li>
 *   <li><b>CONFIRMATION</b> — user answers, AI proposes 2-3 directions, user picks one</li>
 *   <li><b>DECOMPOSITION</b> — AI decomposes the confirmed direction into a topic plan</li>
 * </ol>
 *
 * <p><b>ChatMemory</b>: each call to {@link #discuss} or {@link #finalizeTopicPlan}
 * automatically loads and appends to the conversation history stored in Redis,
 * keyed by the {@code memoryId} (typically {@code "discussion:{sessionId}"}).
 * The AI sees the full conversation context without manual history management.
 */
@SystemMessage("""
        你是「选题讨论Agent」，用户的创作讨论伙伴，而不是代写工具。

        你的核心理念是「先跟用户聊」——不要急于给方案，而是通过对话帮用户理清思路。

        对话阶段与行为规则：

        1.【澄清阶段】当用户的输入比较模糊（没有明确方向、缺少关键信息时）：
           - 提出2-3个具体的、有启发性的澄清问题
           - 问题应该帮助用户思考「为什么想做这个」「给谁看」「想达到什么效果」
           - 不要在这个阶段给出选题方案

        2.【提案阶段】当用户回答了澄清问题，或提供了足够的信息时：
           - 提出2-3个可能的选题方向
           - 每个方向用一句话概括其价值和受众
           - 询问用户倾向于哪个方向

        3.【拆解阶段】当用户确认了某个方向时：
           - 将确认的方向拆解为具体的选题框架
           - 包含：核心选题、切入角度、内容要点、关键词
           - 询问用户是否满意，或需要调整

        4.【完成阶段】当拆解框架得到用户认可时：
           - 提示用户可以结束讨论，生成最终选题方案
           - 说明最终方案将包含3-5个选题候选、趋势关键词和竞品分析

        重要原则：
        - 始终以对话方式回应，像朋友聊天，不要像机器一样罗列要点
        - 每次回复控制在200-400字，保持对话节奏
        - 如果用户的想法不够清晰，宁可多问一轮，也不要急于给方案
        - 在回复开头用【阶段名】标注当前阶段，如【澄清】【提案】【拆解】【完成】
        - 善用追问引导用户深入思考，而非替用户做决定
        """)
public interface DiscussionAgent {

    /**
     * Conduct one turn of the multi-turn discussion.
     *
     * <p>The {@code memoryId} isolates this conversation in Redis-backed ChatMemory.
     * All previous turns are automatically included as context — no manual
     * history management needed.
     *
     * @param memoryId  conversation identifier (e.g., "discussion:session-123")
     * @param userInput the user's message for this turn
     * @return the AI's reply (prefixed with 【阶段名】)
     */
    @UserMessage("{{userInput}}")
    String discuss(@MemoryId String memoryId, @V("userInput") String userInput);

    /**
     * 流式对话（SSE 逐 token 推送）：与 {@link #discuss} 同一套系统提示与记忆，
     * 供前端实现打字机式输出。
     */
    @UserMessage("{{userInput}}")
    TokenStream discussStream(@MemoryId String memoryId, @V("userInput") String userInput);

    /**
     * Finalize the discussion into a structured topic plan.
     *
     * <p>Uses the full conversation history (via ChatMemory) to generate
     * a structured {@link TopicPlanResult}. This should only be called after
     * the discussion has reached the DECOMPOSITION or COMPLETED phase.
     *
     * @param memoryId conversation identifier (must match a prior {@link #discuss} call)
     * @return a structured topic plan with candidates, keywords and analysis
     */
    @UserMessage("""
            基于我们之前的讨论，请生成最终的结构化选题方案。
            请综合对话中确定的方向、切入角度和关键词，调用可用工具进行联网热点调研与竞品分析，
            然后返回包含3-5个选题候选、趋势关键词、竞品分析摘要和推荐方向的结构化结果。
            """)
    TopicPlanResult finalizeTopicPlan(@MemoryId String memoryId);
}
