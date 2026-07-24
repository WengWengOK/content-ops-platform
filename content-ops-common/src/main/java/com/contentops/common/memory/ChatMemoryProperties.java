package com.contentops.common.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for chat memory.
 *
 * <p>Bindable via application.yml under the {@code contentops.chat-memory} prefix:
 * <pre>
 * contentops:
 *   chat-memory:
 *     window-size: 20
 *     ttl-hours: 24
 *     key-prefix: "contentops:chat-memory:"
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.chat-memory")
public class ChatMemoryProperties {

    /** Max number of messages retained per conversation window */
    private int windowSize = 20;

    /** Redis TTL for chat memory entries (hours) */
    private int ttlHours = 24;

    /** Redis key prefix for chat memory */
    private String keyPrefix = "contentops:chat-memory:";
}
