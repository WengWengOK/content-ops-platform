package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 内置意图语料种子：首次启动（语料表为空）时写入常见说法 → 标准术语的映射，
 * 让「意图识别」在冷启动阶段就能靠语料库命中，减少模型调用。
 *
 * <p>用户可在「意图语料库」里增删自己的说法；本种子只在表为空时写入，不会覆盖用户维护的内容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentCorpusSeeder implements ApplicationRunner {

    private static final String[][] SEED = {
            {"咨询", "这个在哪里买"}, {"咨询", "怎么联系你"}, {"咨询", "请问一下这个怎么弄"},
            {"咨询", "多少钱啊"}, {"咨询", "有链接吗"},
            {"求教程", "求教程"}, {"求教程", "出个教程吧"}, {"求教程", "这个怎么做的"},
            {"求教程", "求详细步骤"}, {"求教程", "蹲一个教程"},
            {"售后", "怎么退款"}, {"售后", "质量有问题"}, {"售后", "售后怎么处理"},
            {"售后", "没收到货"}, {"售后", "订单查不到"},
            {"吐槽", "差评"}, {"吐槽", "就这？"}, {"吐槽", "又是标题党"},
            {"吐槽", "这不是恰饭吗"}, {"吐槽", "内容太水了"},
            {"表扬", "太实用了"}, {"表扬", "讲得好清楚"}, {"表扬", "已关注"},
            {"表扬", "学到了谢谢"}, {"表扬", "这期做得不错"},
            {"推广", "互关吗"}, {"推广", "加个微信"}, {"推广", "接广告吗"},
            {"推广", "商务合作怎么联系"},
            {"潜在客户", "想入手"}, {"潜在客户", "蹲一个链接"}, {"潜在客户", "已经下单了"},
            {"潜在客户", "有优惠券吗"}, {"潜在客户", "求平替"},
            {"反馈", "建议改进一下"}, {"反馈", "内容有点浅"}, {"反馈", "希望多讲讲原理"},
            {"反馈", "可以再深入一点"}, {"反馈", "有没有数据支撑"},
            {"无关", "路过看看"}, {"无关", "吃瓜"}, {"无关", "沙发"},
            {"无关", "哈哈哈哈"},
    };

    private final IntentCorpusRepository repository;
    private final IntentCorpusService corpusService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (repository.count() > 0) {
                return;
            }
            int inserted = 0;
            for (String[] row : SEED) {
                if (corpusService.add(null, row[0], row[1]) != null) {
                    inserted++;
                }
            }
            log.info("[IntentCorpus] 内置语料已写入: {} 条", inserted);
        } catch (Exception e) {
            log.warn("[IntentCorpus] 内置语料写入失败（忽略）: {}", e.getMessage());
        }
    }
}