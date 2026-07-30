package com.contentops.common.agent;

import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Agent 间通信协议（多 Agent 协作框架）。
 *
 * <p>定义多 Agent 协作场景下 Agent 之间的消息格式与消息传递机制，支持点对点通信与
 * 广播通信。底层基于内存消息队列（{@link BlockingQueue}）实现，适用于单体部署模式
 * 下的 Agent 协同。
 *
 * <h3>消息格式</h3>
 * <p>每条消息由 {@link Message} record 描述，包含发送方、接收方、消息类型、内容与时间戳。
 *
 * <h3>消息类型</h3>
 * <ul>
 *   <li>{@link MessageType#TASK_ASSIGNMENT} —— 任务分配（主管 → 工作者）</li>
 *   <li>{@link MessageType#RESULT_DELIVERY} —— 结果交付（工作者 → 主管 / 请求方）</li>
 *   <li>{@link MessageType#FEEDBACK} —— 反馈（审稿 / 评审意见）</li>
 *   <li>{@link MessageType#COLLABORATION_REQUEST} —— 协作请求（请求其他 Agent 协助）</li>
 *   <li>{@link MessageType#TERMINATION} —— 终止通知（通知 Agent 结束当前会话）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AgentCommunicationProtocol protocol = new AgentCommunicationProtocol(1000);
 * protocol.register("supervisor");
 * protocol.register("writer");
 * protocol.send(Message.taskAssignment("supervisor", "writer", "撰写 AI 趋势文章"));
 * Message msg = protocol.receive("writer", 1, TimeUnit.SECONDS);
 * }</pre>
 *
 * @see Message
 * @see MessageType
 */
@Slf4j
public class AgentCommunicationProtocol {

    /** 广播接收方通配符，发送到此地址的消息会被投递给所有已注册 Agent。 */
    public static final String BROADCAST = "*";

    /** 每个 Agent 对应一个独立的有界阻塞队列。 */
    private final Map<String, BlockingQueue<Message>> mailboxes = new ConcurrentHashMap<>();

    /** 单个邮箱的容量上限。 */
    private final int queueCapacity;

    /** 接收消息时的默认轮询超时（毫秒）。 */
    private final long defaultPollTimeoutMs;

    /**
     * 构造通信协议实例。
     *
     * @param queueCapacity 单个 Agent 邮箱容量（超出时旧消息会被丢弃并告警）
     */
    public AgentCommunicationProtocol(int queueCapacity) {
        this(queueCapacity, 1000L);
    }

    /**
     * 构造通信协议实例。
     *
     * @param queueCapacity      单个 Agent 邮箱容量
     * @param defaultPollTimeoutMs 默认轮询超时（毫秒）
     */
    public AgentCommunicationProtocol(int queueCapacity, long defaultPollTimeoutMs) {
        this.queueCapacity = Math.max(16, queueCapacity);
        this.defaultPollTimeoutMs = defaultPollTimeoutMs;
        log.info("[AgentComm] 通信协议已初始化, queueCapacity={}, defaultPollTimeoutMs={}",
                this.queueCapacity, this.defaultPollTimeoutMs);
    }

    // ──────────────────────── 注册与注销 ────────────────────────

    /**
     * 注册一个 Agent 邮箱。
     *
     * @param agentName Agent 名称（角色名）
     * @return true 表示注册成功；false 表示该 Agent 已注册
     */
    public boolean register(String agentName) {
        Objects.requireNonNull(agentName, "agentName 不能为 null");
        return mailboxes.putIfAbsent(agentName, new LinkedBlockingQueue<>(queueCapacity)) == null;
    }

    /**
     * 注销一个 Agent 邮箱，并清空其未读消息。
     *
     * @param agentName Agent 名称
     */
    public void unregister(String agentName) {
        BlockingQueue<Message> queue = mailboxes.remove(agentName);
        if (queue != null) {
            queue.clear();
            log.debug("[AgentComm] 已注销 Agent 邮箱: {}", agentName);
        }
    }

    /**
     * 获取所有已注册的 Agent 名称。
     *
     * @return 不可变的 Agent 名称集合
     */
    public Set<String> registeredAgents() {
        return Set.copyOf(mailboxes.keySet());
    }

    // ──────────────────────── 发送 ────────────────────────

    /**
     * 发送一条消息（点对点或广播）。
     *
     * <p>当 {@code message.receiver()} 为 {@link #BROADCAST} 时，消息会被投递给所有已注册 Agent。
     *
     * @param message 待发送消息
     * @return true 表示至少投递给了一个接收方
     */
    public boolean send(Message message) {
        Objects.requireNonNull(message, "message 不能为 null");
        if (BROADCAST.equals(message.receiver())) {
            return broadcast(message);
        }
        BlockingQueue<Message> queue = mailboxes.get(message.receiver());
        if (queue == null) {
            log.warn("[AgentComm] 接收方未注册，消息被丢弃: from={}, to={}",
                    message.sender(), message.receiver());
            return false;
        }
        return offer(queue, message);
    }

    /**
     * 广播一条消息给所有已注册 Agent。
     *
     * @param message 待广播消息（receiver 会被忽略）
     * @return true 表示至少投递给了一个 Agent
     */
    public boolean broadcast(Message message) {
        Objects.requireNonNull(message, "message 不能为 null");
        Message broadcastMsg = new Message(
                message.sender(), BROADCAST, message.type(), message.content(), message.timestamp());
        boolean anyDelivered = false;
        for (Map.Entry<String, BlockingQueue<Message>> entry : mailboxes.entrySet()) {
            anyDelivered |= offer(entry.getValue(), broadcastMsg);
        }
        if (!anyDelivered) {
            log.debug("[AgentComm] 广播无接收方: from={}", message.sender());
        }
        return anyDelivered;
    }

    /**
     * 向队列非阻塞投递消息，队列满时丢弃并告警（背压降级）。
     */
    private boolean offer(BlockingQueue<Message> queue, Message message) {
        boolean delivered = queue.offer(message);
        if (!delivered) {
            log.warn("[AgentComm] 邮箱已满，消息被丢弃: from={}, to={}",
                    message.sender(), message.receiver());
        }
        return delivered;
    }

    // ──────────────────────── 接收 ────────────────────────

    /**
     * 阻塞接收一条消息（使用默认轮询超时）。
     *
     * @param agentName 接收 Agent 名称
     * @return 收到的消息，超时或中断时返回 null
     */
    public Message receive(String agentName) {
        return receive(agentName, defaultPollTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 阻塞接收一条消息。
     *
     * @param agentName 接收 Agent 名称
     * @param timeout   超时时间
     * @param unit      时间单位
     * @return 收到的消息，超时或中断时返回 null
     */
    public Message receive(String agentName, long timeout, TimeUnit unit) {
        BlockingQueue<Message> queue = mailboxes.get(agentName);
        if (queue == null) {
            log.warn("[AgentComm] 接收方未注册: {}", agentName);
            return null;
        }
        try {
            return queue.poll(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[AgentComm] 接收消息被中断: {}", agentName);
            return null;
        }
    }

    /**
     * 非阻塞地取走并移除所有待读消息。
     *
     * @param agentName 接收 Agent 名称
     * @return 已移除的待读消息列表（可能为空）
     */
    public List<Message> drain(String agentName) {
        BlockingQueue<Message> queue = mailboxes.get(agentName);
        if (queue == null) {
            return List.of();
        }
        List<Message> drained = new ArrayList<>();
        queue.drainTo(drained);
        return List.copyOf(drained);
    }

    /**
     * 获取指定 Agent 邮箱中的待读消息数。
     *
     * @param agentName Agent 名称
     * @return 待读消息数（未注册时返回 0）
     */
    public int pending(String agentName) {
        BlockingQueue<Message> queue = mailboxes.get(agentName);
        return queue != null ? queue.size() : 0;
    }

    /**
     * 清空所有邮箱。
     */
    public void clearAll() {
        mailboxes.values().forEach(BlockingQueue::clear);
        log.info("[AgentComm] 已清空所有邮箱");
    }

    /**
     * 批量注册多个 Agent。
     *
     * @param agentNames Agent 名称集合
     */
    public void registerAll(Collection<String> agentNames) {
        if (agentNames != null) {
            agentNames.forEach(this::register);
        }
    }

    // ──────────────────────── 消息类型枚举 ────────────────────────

    /**
     * Agent 间消息类型。
     */
    public enum MessageType {
        /** 任务分配（主管 → 工作者）。 */
        TASK_ASSIGNMENT,
        /** 结果交付（工作者 → 主管 / 请求方）。 */
        RESULT_DELIVERY,
        /** 反馈（评审意见、改进建议）。 */
        FEEDBACK,
        /** 协作请求（请求其他 Agent 协助完成子任务）。 */
        COLLABORATION_REQUEST,
        /** 终止通知（通知 Agent 结束当前会话或任务）。 */
        TERMINATION
    }

    // ──────────────────────── 消息 record ────────────────────────

    /**
     * Agent 间通信消息。
     *
     * <p>采用 Java 21 {@code record} 封装一条消息的完整信息。消息内容为字符串，
     * 便于序列化与跨 Agent 传递结构化文本（如 JSON）。
     *
     * @param sender    发送方 Agent 名称
     * @param receiver  接收方 Agent 名称（{@link AgentCommunicationProtocol#BROADCAST} 表示广播）
     * @param type      消息类型
     * @param content   消息内容
     * @param timestamp 消息时间戳
     */
    public record Message(
            String sender,
            String receiver,
            MessageType type,
            String content,
            Instant timestamp
    ) implements Serializable {

        /**
         * 紧凑构造器：校验必填字段，时间戳默认为当前时刻。
         */
        public Message {
            Objects.requireNonNull(sender, "sender 不能为 null");
            Objects.requireNonNull(receiver, "receiver 不能为 null");
            Objects.requireNonNull(type, "type 不能为 null");
            if (timestamp == null) {
                timestamp = Instant.now();
            }
        }

        /**
         * 构建一条任务分配消息。
         *
         * @param sender   发送方
         * @param receiver 接收方
         * @param content  任务内容
         * @return 消息实例
         */
        public static Message taskAssignment(String sender, String receiver, String content) {
            return new Message(sender, receiver, MessageType.TASK_ASSIGNMENT, content, Instant.now());
        }

        /**
         * 构建一条结果交付消息。
         *
         * @param sender   发送方
         * @param receiver 接收方
         * @param content  结果内容
         * @return 消息实例
         */
        public static Message resultDelivery(String sender, String receiver, String content) {
            return new Message(sender, receiver, MessageType.RESULT_DELIVERY, content, Instant.now());
        }

        /**
         * 构建一条反馈消息。
         *
         * @param sender   发送方
         * @param receiver 接收方
         * @param content  反馈内容
         * @return 消息实例
         */
        public static Message feedback(String sender, String receiver, String content) {
            return new Message(sender, receiver, MessageType.FEEDBACK, content, Instant.now());
        }

        /**
         * 构建一条终止消息。
         *
         * @param sender   发送方
         * @param receiver 接收方
         * @return 消息实例
         */
        public static Message termination(String sender, String receiver) {
            return new Message(sender, receiver, MessageType.TERMINATION, "会话终止", Instant.now());
        }
    }
}
