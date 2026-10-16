"""纯函数单测（标准库 unittest，Python 3.9 也能跑）。

运行： cd content-ops-crewai && python -m unittest discover -s tests -v
"""

import unittest

from app.client import ContentOpsClient, extract_items
from app.tools import (build_report_markdown, format_kb_hits, format_trend_items, format_web_hits,
                       normalize_angles, normalize_fact_check, normalize_topics, parse_json_block)


class ExtractItemsTest(unittest.TestCase):

    def test_list_and_wrapped_shapes(self):
        self.assertEqual(extract_items([{"a": 1}]), [{"a": 1}])
        self.assertEqual(extract_items({"items": [{"a": 1}]}), [{"a": 1}])
        self.assertEqual(extract_items({"trends": [{"a": 1}]}), [{"a": 1}])
        self.assertEqual(extract_items({"unknown": [{"a": 1}]}), [{"a": 1}])
        self.assertEqual(extract_items(None), [])
        self.assertEqual(extract_items({"count": 3}), [])

    def test_unwrap_agent_response(self):
        self.assertEqual(ContentOpsClient._unwrap({"success": True, "data": {"x": 1}}), {"x": 1})
        self.assertEqual(ContentOpsClient._unwrap({"ok": True}), {"ok": True})
        with self.assertRaises(Exception):
            ContentOpsClient._unwrap({"success": False, "error": "登录失败"})


class FormatTest(unittest.TestCase):

    def test_trends(self):
        text = format_trend_items([
            {"title": "AI 提效", "platform": "WEIBO", "rank": 3, "hotValue": 123456, "url": "https://x/1"},
        ])
        self.assertIn("AI 提效", text)
        self.assertIn("WEIBO", text)
        self.assertIn("https://x/1", text)

    def test_trends_empty(self):
        self.assertIn("无热榜数据", format_trend_items([]))

    def test_web_hits_fallbacks(self):
        text = format_web_hits([{"name": "标题", "link": "https://a", "snippet": "摘要"}])
        self.assertIn("标题", text)
        self.assertIn("https://a", text)
        self.assertIn("摘要", text)

    def test_kb_hits(self):
        text = format_kb_hits([{"content": "历史内容", "score": 0.82,
                                "metadata": {"title": "老文章"}, "chunkId": "c-1"}])
        self.assertIn("老文章", text)
        self.assertIn("0.82", text)
        self.assertIn("历史内容", text)


class ParseTest(unittest.TestCase):

    def test_fenced_json(self):
        parsed = parse_json_block('前言\n```json\n{"angles":[{"angle":"A"}]}\n```\n后记')
        self.assertEqual(parsed["angles"][0]["angle"], "A")

    def test_bare_json_with_noise(self):
        parsed = parse_json_block('好的，结果如下：{"topics":[{"title":"T"}]} 以上')
        self.assertEqual(parsed["topics"][0]["title"], "T")

    def test_invalid(self):
        self.assertEqual(parse_json_block("完全不是 JSON"), {})
        self.assertEqual(parse_json_block(None), {})


class NormalizeTest(unittest.TestCase):

    def test_angles_from_strings_and_dicts(self):
        angles = normalize_angles({"angles": ["纯字符串角度", {"角度": "字典角度", "依据": "热榜第 3"}]})
        self.assertEqual(len(angles), 2)
        self.assertEqual(angles[1]["evidence"], "热榜第 3")

    def test_topics_split_string_fields(self):
        topics = normalize_topics({"topics": [
            {"标题": "T1", "平台": "XHS、WECHAT", "来源": "https://a https://b"},
        ]}, limit=3)
        self.assertEqual(topics[0]["title"], "T1")
        self.assertEqual(topics[0]["platforms"], ["XHS", "WECHAT"])
        self.assertEqual(len(topics[0]["sources"]), 2)

    def test_topics_respect_limit(self):
        topics = normalize_topics([{"title": f"T{i}"} for i in range(10)], limit=3)
        self.assertEqual(len(topics), 3)

    def test_fact_check_defaults(self):
        self.assertTrue(normalize_fact_check({"passed": True})["passed"])
        self.assertFalse(normalize_fact_check({"flags": "绝对化用语、无来源数据"})["passed"])
        self.assertTrue(normalize_fact_check(None)["passed"])


class ReportTest(unittest.TestCase):

    def test_report_contains_all_sections(self):
        report = build_report_markdown(
            topic="AI 提效", niche="效率工具", executor="lite",
            hot_angles=[{"angle": "蹭发布会热点", "evidence": "热榜第 2", "source": "https://x"}],
            topics=[{"title": "标题 A", "angle": "角度", "platforms": ["XHS"],
                     "reason": "理由", "sources": ["https://y"]}],
            fact_check={"passed": False, "flags": ["含绝对化用语"], "note": "需人工确认"},
            generated_at="2026-10-15 10:00:00",
        )
        for expected in ("# 选题研究报告：AI 提效", "## 一、热点证据", "## 二、推荐选题",
                         "### 1. 标题 A", "## 三、事实核查", "含绝对化用语", "executor=lite"):
            self.assertIn(expected, report)

    def test_report_when_empty(self):
        report = build_report_markdown(topic="T", niche="", executor="crewai", hot_angles=[],
                                       topics=[], fact_check={"passed": True, "flags": [], "note": ""},
                                       generated_at="now")
        self.assertIn("本次未获得有效热点角度", report)
        self.assertIn("本次未产出有效选题", report)


if __name__ == "__main__":
    unittest.main()
