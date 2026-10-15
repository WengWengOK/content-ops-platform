# 知识库问答 + RAG 错误自动识别

> 目标：**全自动识别 → 人工复核**。不做自动修正，因为误判成本不对称：
> 把对的标成错会浪费复核时间，把错的放过用户可能照着错步骤操作。
> 等人工复核样本攒够，再考虑训练小分类器做自动判别。

---

## 一、四类错误

| 类型 | 表现 | 识别手段 |
|------|------|----------|
| 检索错误 | 正确答案没召回 / 召回不相关片段 | 检索侧信号（相关条数、最高分、改写轮次、双路分歧） |
| 生成幻觉 | 检索不足，模型按通用知识编造步骤/菜单/参数 | 答案-证据一致性校验（覆盖率 + 数字核对） |
| 引用不实 | 引用本次检索结果里不存在的章节 | 引用校验（编号越界 / 零引用） |
| 拒答误判 | 本来能答却拒答，或该说"不知道"却硬编 | 模型拒答识别 + 检索充分性对照 |

---

## 二、五层识别（在线三层 + 归因一层 + 离线一层）

### 1. 检索侧：`RetrievalRelevanceEvaluator`

把被忽略的原始信号显式化，命中即写入「高风险检索日志」：

| 信号 | 触发条件 |
|------|----------|
| `NO_RESULT` | 检索无任何命中 |
| `LOW_RELEVANT_COUNT` | 相关条数 < `min-relevant-count` |
| `LOW_TOP_SCORE` | 最高分 < `min-top-score`（向量路相似度偏低） |
| `REWRITE_EXHAUSTED` | 改写 `max-rewrite-rounds` 轮后仍不充分 |
| `PATH_DIVERGENT` | 关键词路 / 向量路 top-K 重合度 < `path-divergence-threshold` |
| `MODEL_REFUSED` | 检索判定「找得到」，模型却答复「答不了」→ 知识库盲区 |

改写不充分时的二次召回用「伪相关反馈」兜底：把命中最强证据的标题拼进查询（`HyDE / Multi-query` 变体用尽后生效）。

### 2. 引用校验：`CitationGuard`

答案必须用 `[n]` 标注来源；校验编号是否落在本次检索结果内，并统计引用数。
零引用（有事实陈述却无出处）与越界引用都算「引用不实」。
**拒答样本不参与该判定**（拒答本就不该有引用）。

### 3. 忠实度校验：`AnswerFaithfulnessChecker`（核心）

把答案拆成句子/步骤 → 逐条核对是否被证据支持 → 输出 `支持 / 部分支持 / 不支持`：

- **度量用「证据覆盖率」而不是 Jaccard**：证据是几百字、结论是一句，Jaccard 的分母被长证据撑大，
  逐字复述证据的**正确答案也会被判成不支持**（实测 0.10 左右）。覆盖率 = |结论∩证据| / |结论|。
- **数字必须被证据支持**：结论里出现证据中没有的数字（时长、价格、序号），最高只能判「部分支持」——
  这是幻觉的高发区。
- **引用标注先剥离**：`[1]` 不能当作"数字"参与核对。
- 客套/拒答/「我帮你确认一下」类句子不是事实结论，不参与核查。

比例 → 风险：不支持占比 ≥ `unsupported-ratio-threshold` → `HIGH`（进复核清单）；
「不支持 + 部分支持」占比 > `partial-ratio-threshold` → `MEDIUM`（抽样）。

可选 `llm-judge-enabled`：对规则置信度落在模糊带的结论再送强模型复核，默认关闭（在线全量跑 judge 成本高）。

### 4. 用户隐式信号归因：`QaImplicitSignalService`

不等用户点踩也能捕获问题：

| 信号 | 触发 |
|------|------|
| `EXPLICIT_DISLIKE` | 用户点踩（0.9） |
| `DOUBT_REPHRASE` | 追问出现质疑词（不对吧/还是找不到/没有用…），上一条问答被标疑似错误（0.8） |
| `REPEAT_QUESTION` | 时间窗内重复提问（相似度 ≥ `repeat-similarity`）（0.75） |
| `CITATION_BOUNCE` | 点开引用章节停留 < `citation-dwell-ms`（0.55） |
| `HANDOFF_CONFIRMED` | 转人工后实施确认答案错误（0.95） |

置信度 ≥ 0.7 的信号会把对应问答打上 `suspected_error`，直接进高风险清单——这就是**人工复核清单的样本来源**。

### 5. 离线挖掘：`QaOfflineMiner`

- 对**拒答 query** 聚类 → 知识库盲区清单；
- 对**引用失败 / 忠实度低 / 疑似错误**样本聚类 → 判断是切分、检索还是 Prompt 问题；
- `offline/promote` 把错误样本写进 `contentops_llm_eval_case`（stage=`kb-qa`），改检索或 Prompt 后跑回归。
- 聚类用字二元组 Jaccard + 并查集（无模型依赖、可复现）；**生产可替换为向量聚类**，当前阈值下
  同义改写（"退款怎么申请" vs "退款要怎么申请"）能聚到一起，纯词面差异大的同类问题会漏（见已知限制）。

---

## 三、接口（`/api/v1/kb/qa`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/ask` | 提问：检索 → 充分性判断 → 接地生成 → 引用校验 → 忠实度校验 → 落库 |
| GET | `/{qaId}` | 详情（含全部质量信号 + 反馈信号） |
| GET | `/records` | 列表：`reviewState` / `riskLevel` / `refusedOnly` / `suspectedOnly` |
| GET | `/risk-log` | 高风险检索日志（拒答 / 检索侧信号 / 高风险答案 / 疑似错误） |
| GET | `/review-queue` | 人工复核清单（默认 `NEEDS_REVIEW`） |
| PUT | `/{qaId}/review` | 人工复核完成 → `REVIEWED` |
| POST | `/{qaId}/recheck` | 复检：用当前检索结果重标已存答案（**不改答案文本**） |
| POST | `/{qaId}/feedback` | 显式点踩 |
| POST | `/{qaId}/citation-click` | 引用停留时长（隐式信号） |
| POST | `/{qaId}/handoff` | 转人工结果回填（确认错误 → 疑似错误样本） |
| GET | `/stats` | 总量 / 拒答 / 高风险 / 待复核 / 疑似错误 + 信号分布 |
| POST | `/offline/cluster` | 聚类找盲区（`scope=REFUSED/HIGH_RISK/INVALID_CITATION/SUSPECTED/ALL`） |
| POST | `/offline/promote` | 错误样本并入回归评测集 |
| GET | `/offline/report` | 盲区总览 |

---

## 四、数据模型

```
contentops_kb_qa                  一次问答 = 一行（含全部质量信号）
  question / answer / refused / source(llm|template|refuse)
  citations         引用明细 JSON（index/chunkId/title/docId/score）
  citation_check    引用校验 JSON
  retrieval         检索侧 JSON（relevantCount/sufficient/signals/reason/rewriteRounds/queries）
  faithfulness      忠实度 JSON（比例 + 每条结论判定）
  risk_level / review_state / suspected_error / suspected_reason
  trace_id / latency_ms

contentops_kb_qa_signal           反馈信号（点踩/追问/重复提问/引用秒退/转人工确认）
  qa_id / signal_type / confidence / detail / related_qa_id
```

---

## 五、配置（`contentops.knowledge.qa.*`）

```yaml
contentops:
  knowledge:
    qa:
      enabled: true
      top-k: 5
      relevant-score-threshold: 0.5
      min-relevant-count: 1
      min-top-score: 0.35
      max-rewrite-rounds: 2
      refuse-on-insufficient: true
      path-divergence-threshold: 0.34
      faithfulness:
        support-threshold: 0.6      # 证据覆盖率
        partial-threshold: 0.3
        unsupported-ratio-threshold: 0.3
        partial-ratio-threshold: 0.5
        llm-judge-enabled: false
      implicit:
        repeat-window-minutes: 30
        repeat-similarity: 0.45
        citation-dwell-ms: 2000
```

---

## 六、实测（本地真实链路，产品手册 1 chunk）

| 问题 | 结果 |
|------|------|
| 怎么给成员分配角色？ | `source=llm`，覆盖率 0.936 → `SUPPORTED`，`risk=NONE` |
| 如何导出作品的 Excel 报表？ | 模型拒答 → `refused=true`，`signals=[MODEL_REFUSED]`，`risk=MEDIUM` |
| 不对吧，还是找不到角色权限在哪 | 拒答 + `DOUBT_REPHRASE` 信号，上一条问答被标 `suspected_error` |
| 把答案改成编造的「灰度发布白名单 + 工单 + 15 个工作日」后 recheck | 覆盖率 0.216 → `UNSUPPORTED`，引用 `[9]` 越界 → `risk=HIGH`、`NEEDS_REVIEW`，**答案文本未改动** |
| 人工复核闭环 | `PUT /review` → `REVIEWED`，`NEEDS_REVIEW` 归零 |
| 离线聚类（REFUSED） | 2 簇：角色权限入口 / 报表导出（对应两块知识库盲区） |
| 回归集 | `promote` 写入 `contentops_llm_eval_case`，stage=`kb-qa` |

过程中修掉的两个真实误判（有单测回归保护）：

1. 长证据 + 短结论用 Jaccard → 逐字复述的正确答案被判「编造」（改用覆盖率）。
2. 模型主动说「知识库中没有找到」被当作「引用不实」判 HIGH（改为拒答 → `MODEL_REFUSED` 盲区信号）。

---

## 七、已知限制

| 限制 | 说明与下一步 |
|------|--------------|
| 覆盖率是词面度量 | 同义改写（完全换词）可能判「部分支持」；下一步用 NLI 蕴含模型或 embedding 相似度替换规则 |
| 数字核对是硬匹配 | 「48 小时」与「两天」不同形，会被判部分支持；可加单位换算表 |
| 在线 LLM judge 默认关闭 | 高风险/模糊样本才送模型复核，想更准就在 `llm-judge-enabled` 打开并调 `llm-judge-max-claims` |
| 聚类是词面聚类 | 同义不同词的盲区会漏；生产建议换成向量聚类（复用知识库的 BGE 嵌入） |
| 向量库当前为内存模式 | 本机未装 pgvector 时走 in-memory 回退，重启后需重新摄入；装好 pgvector 即为持久化 |
| 前端面板未做 | 目前是接口 + 清单，前端「知识库问答质量」面板是下一步 |
