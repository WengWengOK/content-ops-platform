package com.contentops.common.json;

import dev.langchain4j.internal.Json.JsonCodec;
import dev.langchain4j.spi.json.JsonCodecFactory;

/**
 * 注册宽松 JSON Codec 的 SPI 工厂（替换 LangChain4j 默认解析器）。
 */
public class LenientJsonCodecFactory implements JsonCodecFactory {

    @Override
    public JsonCodec create() {
        return new LenientJsonCodec();
    }
}
