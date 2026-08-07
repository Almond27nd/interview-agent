/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BM25 关键词检索器（与 Go 版本一致）
 * - k1 = 1.5（控制词频饱和度）
 * - b = 0.75（控制文档长度归一化）
 */
public class BM25Retriever {

    // jieba 分词器：无状态、线程安全，全局复用一个实例即可，避免每次 tokenize 都重新加载词典
    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    // 分词结果里如果整个 token 都是标点/空白（例如残留的全角/半角标点），直接过滤掉
    private static final Pattern PUNCTUATION_ONLY = Pattern.compile("^[\\p{P}\\s]+$");

    // 中文常见停用词：这些词几乎不携带检索区分度（IDF 极低），过滤后能减少对 BM25 打分的噪音干扰
    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "也", "就", "都", "而", "及",
            "但", "并", "着", "为", "对", "上", "中", "下", "又", "或", "被",
            "把", "让", "从", "到", "这", "那", "个", "之", "其", "并且"
    );

    private List<RagDocument> documents = new ArrayList<>();
    private Map<String, List<DocTF>> index = new HashMap<>(); // 倒排索引
    private int[] docLen;
    private double avgDL;
    private final int topK;
    private final double k1 = 1.5;
    private final double b = 0.75;

    private static class DocTF {
        int docIdx;
        int tf;

        DocTF(int docIdx, int tf) {
            this.docIdx = docIdx;
            this.tf = tf;
        }
    }

    public BM25Retriever(int topK) {
        this.topK = topK > 0 ? topK : 10;
    }

    public List<RagDocument> getDocuments() {
        return documents;
    }

    /**
     * 构建 BM25 倒排索引
     */
    public void indexDocuments(List<RagDocument> docs) {
        this.documents = new ArrayList<>(docs);
        this.index = new HashMap<>();
        this.docLen = new int[docs.size()];

        int totalLen = 0;
        for (int i = 0; i < docs.size(); i++) {
            List<String> tokens = tokenize(docs.get(i).getContent());
            docLen[i] = tokens.size();
            totalLen += tokens.size();

            Map<String, Integer> tfMap = new HashMap<>();
            for (String t : tokens) {
                tfMap.merge(t, 1, Integer::sum);
            }

            for (Map.Entry<String, Integer> entry : tfMap.entrySet()) {
                index.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(new DocTF(i, entry.getValue()));
            }
        }

        avgDL = docs.isEmpty() ? 0 : (double) totalLen / docs.size();
    }

    /**
     * BM25 检索
     */
    public List<RagDocument> retrieve(String query) {
        if (documents.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTokens = tokenize(query);
        int n = documents.size();
        double[] scores = new double[n];

        for (String term : queryTokens) {
            List<DocTF> postings = index.get(term);
            if (postings == null) continue;

            // IDF = log((N - df + 0.5) / (df + 0.5) + 1)
            double df = postings.size();
            double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1);

            for (DocTF p : postings) {
                double tf = p.tf;
                double dl = docLen[p.docIdx];
                // BM25 score = IDF * (tf * (k1+1)) / (tf + k1 * (1 - b + b * dl/avgDL))
                double score = idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgDL));
                scores[p.docIdx] += score;
            }
        }

        // 按分数降序排列
        List<int[]> ranked = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (scores[i] > 0) {
                ranked.add(new int[]{i});
            }
        }
        ranked.sort((a, bb) -> Double.compare(scores[bb[0]], scores[a[0]]));

        int limit = Math.min(topK, ranked.size());
        List<RagDocument> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int idx = ranked.get(i)[0];
            RagDocument doc = documents.get(idx);
            RagDocument copy = RagDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .metadata(doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>())
                    .userId(doc.getUserId())
                    .sourceFile(doc.getSourceFile())
                    .build();
            copy.getMetadata().put("_bm25_score", scores[idx]);
            results.add(copy);
        }

        return results;
    }

    /**
     * 追加文档并重建索引
     */
    public void appendDocuments(List<RagDocument> newDocs) {
        documents.addAll(newDocs);
        indexDocuments(documents);
    }

    /**
     * 删除指定来源文件的所有文档，并重建索引（配合 Milvus 侧“按 source_file 覆盖式更新”的语义，
     * 避免用户重复上传同一份题库导致旧版本题目一直堆积在索引里）
     */
    public void removeBySourceFile(String sourceFile) {
        if (sourceFile == null || documents.isEmpty()) {
            return;
        }
        List<RagDocument> remaining = documents.stream()
                .filter(d -> !sourceFile.equals(d.getSourceFile()))
                .collect(Collectors.toList());
        if (remaining.size() != documents.size()) {
            indexDocuments(remaining);
        }
    }

    /**
     * 中文分词：基于 jieba（搜索模式），替代此前"标点转空格再按空格切"的朴素实现。
     * 相比朴素实现，能把中文长句正确切分成有意义的词语（例如"MVCC通过版本链实现无锁读"
     * 会被切成 ["mvcc","通过","版本链","实现","无锁","读"] 这类独立 token，而不是整句当一个大 token），
     * 从而让倒排索引真正对中文内容生效，不再只对被标点/空格天然隔开的英文词起作用。
     */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        String lower = text.toLowerCase();
        List<SegToken> segTokens = SEGMENTER.process(lower, JiebaSegmenter.SegMode.SEARCH);

        List<String> tokens = new ArrayList<>(segTokens.size());
        for (SegToken st : segTokens) {
            String w = st.word.trim();
            if (w.isEmpty() || PUNCTUATION_ONLY.matcher(w).matches() || STOPWORDS.contains(w)) {
                continue;
            }
            tokens.add(w);
        }
        return tokens;
    }
}
