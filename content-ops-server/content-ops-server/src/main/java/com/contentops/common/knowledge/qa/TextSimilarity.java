package com.contentops.common.knowledge.qa;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量文本相似度工具（无模型依赖，确定性可测）。
 *
 * <p>用于三处：忠实度校验（结论 vs 证据）、隐式信号（重复提问）、离线聚类（盲区聚类）。
 * 采用「字二元组 Jaccard」，对中文短文本比词袋更稳，且不依赖分词器。
 */
public final class TextSimilarity {

    private static final Pattern NOISE = Pattern.compile("[\\s\\p{Punct}，。！？；：、“”‘’（）《》【】…—～·　]");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private TextSimilarity() {
    }

    /** 归一化：去空白与标点，转小写。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return NOISE.matcher(text.toLowerCase()).replaceAll("");
    }

    /** 字二元组集合（长度为 1 的文本退化为单字集合）。 */
    public static Set<String> bigrams(String text) {
        String normalized = normalize(text);
        Set<String> grams = new HashSet<>();
        if (normalized.isEmpty()) {
            return grams;
        }
        if (normalized.length() == 1) {
            grams.add(normalized);
            return grams;
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    /** 字二元组 Jaccard 相似度（0~1）。 */
    public static double jaccard(String a, String b) {
        Set<String> left = bigrams(a);
        Set<String> right = bigrams(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        int union = left.size() + right.size() - intersection.size();
        return union <= 0 ? 0 : (double) intersection.size() / union;
    }

    /**
     * 覆盖率：{@code claim} 有多少被 {@code evidence} 覆盖（|交集| / |claim|）。
     *
     * <p>忠实度核对必须用它而不是 Jaccard：证据通常是一大段（几百字），结论只有一句，
     * 用 Jaccard 时分母被长证据撑大，**逐字复述证据的正确答案也会被判成不支持**（实测 0.1 左右）。
     * 覆盖率只看「结论是否落在证据里」，这才是「是否被证据支持」的正确度量。
     */
    public static double containment(String claim, String evidence) {
        Set<String> claimGrams = bigrams(claim);
        Set<String> evidenceGrams = bigrams(evidence);
        if (claimGrams.isEmpty() || evidenceGrams.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(claimGrams);
        intersection.retainAll(evidenceGrams);
        return (double) intersection.size() / claimGrams.size();
    }

    /** 抽取文本中的数字（含小数），用于「数字是否被证据支持」的核对。 */
    public static Set<String> numbers(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        if (text == null) {
            return numbers;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    /** 四舍五入到 3 位小数。 */
    public static double round3(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
