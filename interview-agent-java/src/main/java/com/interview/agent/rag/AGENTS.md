# rag 包约束

## RRF 融合

- **`RRFusion.RRF_CONSTANT = 60`**（k 值）
  同时服务题库检索和记忆召回，改动面大，**不要轻易改**。

- **平局消解在 `MemoryRecallService` 里做，不改 `RRFusion` 本身**
  RRF 只看排名，各路排名互为镜像时完全平局，`RRFusion` 内部用 HashMap 聚合 ⇒
  平局项顺序取决于哈希迭代 ⇒ 同一输入可能产出不同出题顺序（不可复现）。
  补一层确定性次级排序：RRF 分 → priority → topic 字典序。

## 检索管道

- **题库检索命中时原题照搬，不得改编**
  这是"Assembler + Critic"拆法而非"Retriever + Generator"的原因之一（见 ADR-001）。
  题库命中时 Generator 近乎透传，职责重叠。

- **Rerank 用 LLM 全量重排**，RRF 是备选方案
  主路径：Milvus + BM25 双路去重合并 → LLM Rerank 取 top1
  备选：RRF 融合（记忆召回场景使用）

## 向量库

- **Milvus collection 按用户隔离**（collection 名含 userId）
  不同用户的题库互不干扰。

- **embedding 维度 1024，COSINE 相似度**
  换 embedding 模型必须重建 collection 并重跑离线评估。
