package com.contentops.orchestrator.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 循环优化工作流图定义。
 *
 * <p>使用 LangGraph4j {@link StateGraph} 构建完整的内容运营循环优化图：
 *
 * <pre>
 *   START → topic → content → image → publish → analysis → optimize
 *         → cycle-increment → [条件边]
 *           ├─ cycleCount < maxCycles → feedback-inject → topic（循环）
 *           └─ cycleCount >= maxCycles → END（终止）
 * </pre>
 *
 * <p><b>原生循环控制</b>：通过条件边（addConditionalEdges）实现，
 * 无需手动管理循环计数器或阶段切换 —— LangGraph4j 自动处理。
 *
 * <p><b>人机协同</b>：通过 interruptBefore 在 content 和 image 节点前暂停，
 * 等待人工确认大纲/风格方向后恢复执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentOpsGraph {

    private final AgentNodeAdapter nodeAdapter;

    /**
     * 构建循环优化工作流图并编译。
     *
     * @return 编译后的图，可调用 {@code invoke()} 执行
     * @throws Exception 图编译失败
     */
    public CompiledGraph<ContentOpsState> buildGraph() throws Exception {

        return new StateGraph<>(ContentOpsState.SCHEMA, ContentOpsState::new)

            // ─── 6 个阶段节点 ───
            .addNode("topic",   node_async(nodeAdapter.syncNode("topic-planning")))
            .addNode("content", node_async(nodeAdapter.syncNode("content-creation")))
            .addNode("image",   node_async(nodeAdapter.syncNode("image-design")))
            .addNode("publish", node_async(nodeAdapter.syncNode("publishing")))
            .addNode("analysis", node_async(nodeAdapter.syncNode("data-analysis")))
            .addNode("optimize", node_async(nodeAdapter.syncNode("optimization")))

            // ─── 循环计数节点：递增 cycleCount，快照当前轮次产物 ───
            .addNode("cycle-increment", node_async(state -> {
                int current = state.cycleCount();
                int next = current + 1;

                Map<String, Object> prevArtifacts = new HashMap<>(state.accumulatedArtifacts());

                log.info("[Graph] Cycle incremented: {} → {} (max={})",
                    current, next, state.maxCycles());

                return Map.of(
                    ContentOpsState.CYCLE_COUNT, next,
                    ContentOpsState.CYCLE_HISTORY, prevArtifacts
                );
            }))

            // ─── 反馈注入节点：将上一轮 optimization/analysis 结果注入 inputs ───
            .addNode("feedback-inject", node_async(state -> {
                Map<String, Object> artifacts = state.accumulatedArtifacts();
                Map<String, Object> inputs = new HashMap<>(state.inputs());

                Object optimizeResult = artifacts.get("optimization");
                Object analysisResult = artifacts.get("data-analysis");

                if (optimizeResult != null) {
                    inputs.put("previousOptimization", optimizeResult);
                    log.info("[Graph] Injected previousOptimization into cycle {}", state.cycleCount());
                }
                if (analysisResult != null) {
                    inputs.put("previousAnalysis", analysisResult);
                }

                inputs.put("cycleContext", Map.of(
                    "cycleNumber", state.cycleCount(),
                    "maxCycles", state.maxCycles()
                ));

                return Map.of(ContentOpsState.INPUTS, inputs);
            }))

            // ─── 线性边 ───
            .addEdge(StateGraph.START, "topic")
            .addEdge("topic", "content")
            .addEdge("content", "image")
            .addEdge("image", "publish")
            .addEdge("publish", "analysis")
            .addEdge("analysis", "optimize")
            .addEdge("optimize", "cycle-increment")

            // ─── 条件边：循环终止判断 ───
            .addConditionalEdges("cycle-increment",
                state -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    if (state.shouldTerminate()) {
                        log.info("[Graph] Cycle terminated at {} (max={})",
                            state.cycleCount(), state.maxCycles());
                        return "end";
                    }
                    log.info("[Graph] Continuing to cycle {}", state.cycleCount());
                    return "loop";
                }),
                Map.of(
                    "end", StateGraph.END,
                    "loop", "feedback-inject"
                )
            )

            // feedback-inject → topic（闭合循环）
            .addEdge("feedback-inject", "topic")

            // ─── 编译配置 ───
            .compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .interruptBefore("content", "image")
                .build()
            );
    }
}
