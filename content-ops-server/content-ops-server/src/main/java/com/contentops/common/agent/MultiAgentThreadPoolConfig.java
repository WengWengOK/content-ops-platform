package com.contentops.common.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多 Agent 协作框架自定义线程池配置。
 *
 * <p>提供独立于 Spring 默认 {@code TaskExecutor} 的有界线程池 Bean
 * {@code multiAgentExecutor}，专供 {@link MultiAgentOrchestrator} 与
 * {@link PlanAndExecuteAgent} 进行异步并行任务调度。
 *
 * <p><b>设计要点：</b>
 * <ul>
 *   <li>使用有界工作队列 + {@link ThreadPoolExecutor.CallerRunsPolicy} 拒绝策略，
 *       队列满时由调用线程执行，实现背压，避免 OOM</li>
 *   <li>守护线程，避免阻止 JVM 退出</li>
 *   <li>所有参数通过 {@link MultiAgentProperties.ThreadPoolConfig} 绑定</li>
 *   <li>通过 {@code destroyMethod = "shutdown"} 由 Spring 容器管理优雅关闭</li>
 * </ul>
 *
 * @see MultiAgentProperties
 * @see MultiAgentOrchestrator
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MultiAgentThreadPoolConfig {

    private final MultiAgentProperties properties;

    /** 线程池 Bean 名称，供 {@code @Qualifier("multiAgentExecutor")} 注入。 */
    public static final String EXECUTOR_BEAN_NAME = "multiAgentExecutor";

    /**
     * 创建并暴露多 Agent 协作专用线程池。
     *
     * <p>Bean 销毁时由 Spring 自动调用 {@link ExecutorService#shutdown()} 优雅关闭。
     *
     * @return 线程池 {@link ExecutorService}
     */
    @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ExecutorService multiAgentExecutor() {
        MultiAgentProperties.ThreadPoolConfig cfg = properties.getThreadPool();
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, cfg.getThreadNamePrefix() + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        ExecutorService executor = new ThreadPoolExecutor(
                cfg.getCorePoolSize(),
                cfg.getMaxPoolSize(),
                cfg.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(cfg.getQueueCapacity()),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("[MultiAgent] 线程池已初始化: core={}, max={}, queue={}, prefix={}",
                cfg.getCorePoolSize(), cfg.getMaxPoolSize(), cfg.getQueueCapacity(),
                cfg.getThreadNamePrefix());
        return executor;
    }
}
