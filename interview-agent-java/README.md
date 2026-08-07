# InterviewAgent · AI 模拟面试系统（Java 版）

基于 **Java 21 + Spring Boot 3.4 + [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba)** 构建的 AI 模拟面试系统。用户上传简历、输入目标岗位 JD，系统自动完成简历匹配、智能出题、多轮技术面试、实时评分，并在面试结束后生成评估报告与个性化复习计划。涵盖多 Agent 协作编排、RAG 多路召回、动态难度调节、Agent 记忆系统等大模型应用核心能力，底层大模型统一使用通义千问 DashScope。

---

## 系统架构

```
用户（浏览器）
      │
      ▼
前端 interview-agent-web  (React + Vite，:5173)
      │  /api、/ws 代理
      ▼
后端 Spring Boot  (:9090，WebSocket 实时通信)
      │
      ▼
Agent 编排层（Spring AI Alibaba Graph / StateGraph，DAG 串联）
  ┌──────────────────────────────────────────────────────────┐
  │  JD 分析 → 简历匹配 → 历史薄弱点召回 → 出题规划（两阶段）   │
  │                                    ↓                       │
  │            面试官（多轮问答 + 动态难度调节 + 追问）          │
  │                                    ↓                       │
  │                   评估报告 → 复习规划（ReactAgent）          │
  └──────────────────────────────────────────────────────────┘
      │
      ▼
基础能力层
  ┌────────────────────┬────────────────────┬───────────────────┐
  │ RAG 多路召回         │ 记忆系统            │ Skill / 工具       │
  │ Milvus 向量 + BM25   │ 短期滑窗 + 长期画像  │ 4 个内置 Skill     │
  │ 去重合并 + LLM 重排  │ Redis + MySQL       │ GitHub / 网页抓取  │
  └────────────────────┴────────────────────┴───────────────────┘
      │
      ▼
基础设施层（Docker Compose 一键启动）
  ┌───────────┬───────────┬───────────┐
  │  Milvus   │   Redis   │   MySQL   │
  │ 向量数据库 │  会话缓存  │  持久化    │
  └───────────┴───────────┴───────────┘
```

---

## 核心特性

- **多 Agent 协作**：7 个专职 Agent（聊天 + JD 分析 / 简历匹配 / 出题规划 / 面试官 / 评估 / 复习规划）通过 Spring AI Alibaba Graph 的 StateGraph 编排成有向图，由中心编排器按固定流程串联，流程确定、各 Agent 可独立调优。
- **RAG 多路召回**：Milvus 向量检索（text-embedding-v3，1024 维，COSINE）+ 自研内存 BM25 关键词检索双路并行，结果按文档 ID 去重合并后交由 LLM 做**全量重排**取题；并实现 RRF（k=60）融合器作为备选方案。
- **RAG 离线评估**：基于人工标注数据集计算 Recall@K / MRR，支撑 TopK、分词等参数的 A/B 对比；另实现基于 LLM 的三维质量评估（忠实度 / 相关性 / 完整性）。
- **动态难度调节**：出题阶段预生成按难度分档铺满的候选题池，面试时由阶段调度器按 basic → experience → design 三阶段从候选池自适应取题，连续答对升一档、连续答错降一档。
- **Agent 记忆系统**：短期对话记忆（20 条滑动窗口）+ 长期用户画像与薄弱点追踪（得分 <60 记录 / ≥80 移除 / 30 天淘汰），Redis 缓存热数据 + MySQL 持久化，Cache-Aside 统一管理。
- **Skill 技能系统**：有状态多轮交互，4 个内置 Skill（快速测验 / 概念教学 / 项目亮点 / 技术对比），通过 SkillRegistry 注册中心按优先级匹配，可插拔扩展。
- **工具集成**：GitHub 项目搜索工具（通过 FunctionToolCallback 注册，复习规划 Agent 的 ReactAgent 自主调用，推荐学习资源）、网页抓取工具（JD 链接基础抓取）。
- **WebSocket 实时通信**：面试编排在独立线程池中执行，通过阻塞队列与回调实现「人在环」的逐题问答，阶段进展实时推送前端。
- **认证与持久化**：Spring Security + JWT + bcrypt 登录鉴权；每次面试的评估报告与复习计划写入 MySQL，支持历史查询。

---

## 技术栈

| 类别 | 选型 | 用途 |
|------|------|------|
| 语言 | Java 21 | 主语言 |
| 应用框架 | Spring Boot 3.4.1 | Web / WebSocket / 依赖注入 / 数据访问 |
| AI 框架 | Spring AI Alibaba 1.1.2.0 | Agent 编排（Graph）/ 工具调用（ReactAgent）/ RAG |
| 大模型 | 通义千问 DashScope | LLM 推理（qwen-plus，可换 qwen-max） |
| Embedding | text-embedding-v3 | 文本向量化（1024 维） |
| 向量数据库 | Milvus 2.4 | 向量存储与 COSINE 检索 |
| 缓存 | Redis 7 | 会话缓存 / 用户画像缓存 / 文件去重哈希 |
| 持久化 | MySQL 8.0 | 用户 / 面试记录 / 评估报告 / 复习计划 |
| 认证 | Spring Security + JWT（jjwt） | 登录鉴权 |
| 容器化 | Docker Compose | 一键部署基础设施 |
| 前端 | React + Vite + TypeScript | Web 交互界面（独立仓库 interview-agent-web） |

---

## 项目结构

```
interview-agent-java/
├── src/main/java/com/interview/agent/
│   ├── InterviewAgentApplication.java   # Spring Boot 启动类
│   ├── agent/                           # Agent 实现
│   │   ├── ChatAgent.java               #   聊天 Agent（日常对话 / 面试引导）
│   │   ├── JDAnalyzer.java              #   JD 分析
│   │   ├── ResumeMatcher.java          #   简历匹配
│   │   ├── QuestionPlanner.java        #   出题规划 + 动态难度调节算法
│   │   ├── Interviewer.java            #   面试官（提问 / 评分 / 追问 / 画像更新）
│   │   ├── Evaluator.java              #   评估报告生成
│   │   ├── ReviewPlanner.java          #   复习计划生成（ReactAgent + 工具）
│   │   └── IntentRouter.java           #   意图路由（关键词匹配，非 LLM）
│   ├── rag/                             # RAG 多路召回
│   │   ├── MilvusStore.java            #   Milvus 向量检索（按用户隔离）
│   │   ├── BM25Retriever.java          #   BM25 内存倒排索引
│   │   ├── BM25Manager.java            #   按用户管理 BM25 实例
│   │   ├── Reranker.java               #   LLM 全量重排
│   │   ├── RRFusion.java               #   RRF 融合器（备用）
│   │   └── eval/                       #   离线评估（Recall@K / MRR / 三维质量评估）
│   ├── memory/                          # 记忆系统
│   │   ├── ShortTermMemory.java        #   短期记忆（滑动窗口）
│   │   ├── LongTermMemory.java         #   长期记忆（画像 / 薄弱点追踪）
│   │   ├── RedisStore.java             #   Redis 存储
│   │   ├── MySQLStore.java             #   MySQL 持久化
│   │   └── CombinedStore.java          #   组合存储（Cache-Aside）
│   ├── graph/                           # 编排
│   │   ├── Orchestrator.java           #   StateGraph 全局编排
│   │   ├── QuestionPool.java           #   候选题池（难度分桶）
│   │   ├── StageScheduler.java         #   阶段化自适应取题
│   │   └── InterviewCallbacks.java     #   面试过程回调
│   ├── skill/                           # Skill 技能系统（4 个内置 Skill + 注册中心）
│   ├── mcp/                             # 工具：GitHubTool / WebScraperTool
│   ├── loader/                          # 文档加载与题库解析（PDF / DOCX / Web / 题库）
│   ├── auth/                            # JWT 认证
│   ├── handler/                         # WebSocketHandler / 健康检查
│   ├── config/                          # 配置（Milvus / Security / WebSocket / Skill 等）
│   └── model/                           # 数据模型
├── src/main/resources/application.yml   # 应用配置
├── data/questions/                      # 内置面试题库（go / mysql / distributed / mq / redis）
├── data/eval/                           # RAG 评估数据集与 manifest
├── docker-compose.yml                   # 基础设施编排（Milvus + Redis + MySQL）
├── Dockerfile                           # 后端镜像构建（多阶段）
├── Makefile                             # 常用命令
├── pom.xml                              # Maven 依赖
└── .env.example                         # 环境变量模板
```

---

## 快速开始

### 第一步：环境准备

| 工具 | 版本 | 检查命令 | 说明 |
|------|------|----------|------|
| JDK | **21** | `java -version` | 必须 JDK 21；更高版本（如 24/26）会因 Lombok 注解处理器不兼容导致 `mvn` 编译失败。多 JDK 环境用 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 指定 |
| Maven | 3.9+ | `mvn -v` | 构建后端 |
| Docker Desktop | 最新 | `docker compose version` | 跑基础设施，确保已启动 |
| Node.js | 18+ | `node -v` | 跑前端 |
| 通义千问 API Key | — | — | 见下方 |

**获取通义千问 API Key**：打开 [DashScope 控制台](https://dashscope.console.aliyun.com/) → 用支付宝/淘宝账号登录 → 「API-KEY 管理」→ 创建新 Key（以 `sk-` 开头）。新用户有免费额度，qwen-plus 足够跑完多次完整面试。

### 第二步：配置环境变量

```bash
cp .env.example .env
```

编辑 `.env`，**只需填入第一行的 API Key**，其余保持默认（与 `docker-compose.yml` 端口一一对应）：

```bash
DASHSCOPE_API_KEY=sk-你的真实key
```

> 项目已集成 `spring-dotenv`，启动时会**自动读取项目根目录的 `.env` 文件**，无需手动 `export` 或 `source`。`.env` 已在 `.gitignore` 中，不会被提交。

环境变量一览：

| 变量 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `DASHSCOPE_API_KEY` | ✅ | — | 通义千问 API Key |
| `LLM_MODEL` | | `qwen-plus` | 对话模型，可换 `qwen-max` |
| `EMBEDDING_MODEL` | | `text-embedding-v3` | Embedding 模型 |
| `MILVUS_HOST` / `MILVUS_PORT` | | `localhost` / `19530` | Milvus 地址 |
| `REDIS_HOST` / `REDIS_PORT` | | `localhost` / `6379` | Redis 地址 |
| `MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | | 见 `.env.example` | MySQL 连接 |
| `JWT_SECRET` | | 内置默认值 | JWT 签名密钥（生产环境务必修改） |
| `GITHUB_TOKEN` | | 空 | GitHub 搜索工具（可选，提高限额） |
| `AUTH_ENABLED` | | `true` | 是否开启登录鉴权 |

### 第三步：启动基础设施

```bash
make infra-up      # = docker-compose up -d，启动 Milvus + Redis + MySQL（含 etcd + minio 共 5 个容器）
```

首次启动需拉取镜像，耐心等 3~5 分钟。查看状态：

```bash
make infra-status  # = docker-compose ps
```

应看到 5 个容器全部 `Up (healthy)`。Milvus 启动较慢，若未健康再等 30 秒。

### 第四步：启动后端

```bash
make run           # = mvn spring-boot:run，启动时自动加载 .env
```

首次会下载依赖（1~2 分钟）。启动成功后后端监听 `http://localhost:9090`。

> **多 JDK 环境**：若默认 `java` 不是 21，先 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 再 `make run`，否则 Lombok 注解处理器会因 JDK 版本过高而编译失败。

### 第五步：启动前端

前端在同级目录的独立仓库 `interview-agent-web`（Vite 已将 `/api`、`/ws` 代理到 `localhost:9090`）：

```bash
cd ../interview-agent-web
npm install        # 首次需安装依赖
npm run dev        # 启动后访问 http://localhost:5173
```

### 第六步：使用

1. **注册 / 登录**：在页面注册账号并登录。
2. **上传题库（可选）**：上传 PDF / TXT / MD 格式的面试题库，系统自动解析、向量化并写入 Milvus + BM25（按用户隔离）。仓库 `data/questions/` 下自带 Go / MySQL / 分布式 / 消息队列 / Redis 五个方向的题库可直接上传。不上传也能面试，系统会由 LLM 直接出题。
3. **开始面试**：填入 JD 与简历，系统自动执行 `JD 分析 → 简历匹配 → 题库检索 → 出题规划 → 逐题面试 → 评估报告 → 复习计划` 全流程。JD 支持三种输入：直接粘贴文本、上传文件（PDF / DOCX）、粘贴招聘链接（基础抓取，JS 渲染页面建议改用粘贴）。
4. **查看结果**：评估报告与复习计划均持久化在 MySQL，可随时查看。

---

## 常用命令（Makefile）

```bash
make run            # mvn spring-boot:run，启动后端
make build          # mvn compile，编译
make package        # mvn package -DskipTests，打包 jar
make test           # mvn test，运行测试
make infra-up       # 启动 Milvus + Redis + MySQL
make infra-down     # 停止基础设施
make infra-status   # 查看容器状态
make docker-build   # 构建后端镜像
make docker-run     # 以容器方式运行后端（--env-file .env）
make clean          # mvn clean
```

### 可观测性（可选）

启动 Langfuse 查看 LLM 调用全链路 Trace（Token 消耗、耗时、Prompt/Response 回放）：

```bash
docker compose -f docker-compose-langfuse.yml up -d   # 启动 Langfuse（端口 3000）
```

在 `.env` 中配置 `OTLP_ENDPOINT=http://localhost:4317`，后端启动后 Spring AI 的 ChatModel 调用会自动产生 trace span 并导出到 Langfuse。不启动 Langfuse 不影响面试流程（fail-open）。

业务指标（难度 fallback 触发率、薄弱点覆盖率、审题打回轮次、配额补全触发率）通过 Prometheus 端点暴露：

```bash
curl http://localhost:9090/actuator/prometheus   # 查看业务指标
```

---

## RAG 离线评估（可选）

项目内置一条命令行评估流水线（`rag/eval/EvalCommandRunner`），用于量化检索质量：解析题库 → 向量化写入 Milvus 并导出 `manifest.json` → 按 topic 采样生成评估数据集 → 跑检索算 Recall@10 / Recall@20 / MRR → 输出 Markdown / JSON 报告。`data/eval/` 下已附带一份数据集与 manifest 示例。通过带命令行参数启动后端触发（如 `--prepare` / `--gen-dataset` / `--execute`），详见 `docs/design/rag_evaluation_plan.md`。

---

## 常见问题

**Q：启动报 `Could not resolve placeholder 'DASHSCOPE_API_KEY'`，或 LLM 调用 401？**
确认项目根目录已有 `.env`（`cp .env.example .env`）且 `DASHSCOPE_API_KEY` 填了真实 key。`spring-dotenv` 会在启动时自动加载，无需手动 export。

**Q：`mvn` 编译报 `ExceptionInInitializerError: ... TypeTag :: UNKNOWN`？**
你的 `mvn` 用了过高的 JDK（如 24/26），Lombok 不兼容。切到 JDK 21：`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 后重试。

**Q：启动卡在连接 Milvus，或报 collection 加载超时？**
Milvus standalone 已知问题：重启后内部节点 ID 变更、旧 collection 元数据未清理导致加载卡住。清空 Milvus 数据卷后重启即可（会清空已上传题库，MySQL / Redis 不受影响）：
```bash
docker-compose down
docker volume rm interview-agent-java_milvus_data
docker-compose up -d
```

**Q：`make infra-up` 后容器一直不 healthy？**
Milvus 首次启动较慢，等 1~2 分钟。仍不健康看日志：`docker-compose logs milvus` / `docker-compose logs mysql`。

**Q：端口被占用（3306 / 6379 / 19530）？**
本地已有同类服务在跑。修改 `docker-compose.yml` 的端口映射，并同步更新 `.env` 中对应地址。

**Q：大模型偶发返回 JSON 解析失败？**
LLM 输出的偶发格式异常，重试即可；频繁出现可在 `.env` 中换更强的模型 `LLM_MODEL=qwen-max`。

**Q：前端打开后连不上后端？**
确认后端已在 `:9090` 运行、基础设施容器健康；前端 Vite 默认把 `/api`、`/ws` 代理到 `localhost:9090`。

---

## 面试流程示意

```
输入 JD + 简历
      │
      ▼
┌─────────────┐    ┌──────────────┐
│  JD 分析     │──▶│  简历匹配     │──▶ 读取历史薄弱点（按 JD 过滤）
└─────────────┘    └──────────────┘
                          │
                          ▼
                   ┌──────────────────────────┐
                   │  出题规划（两阶段）        │
                   │  Phase1 规划方向 + 分档题池 │ ◀── Milvus 向量检索
                   │  Phase2 RAG 检索 / LLM 出题 │ ◀── BM25 关键词检索
                   └──────────┬───────────────┘   去重合并 + LLM 全量重排
                          │
                          ▼
                   ┌──────────────┐
                   │  面试官       │◀── 动态难度调节（连对升 / 连错降）
                   │ (多轮问答+追问)│◀── 短期记忆（对话窗口）+ 候选人画像
                   └──────┬───────┘
                          │
            ┌─────────────┴──────────────┐
            ▼                            ▼
     ┌─────────────┐          ┌──────────────┐
     │  评估报告    │          │  复习规划     │◀── ReactAgent + GitHub 工具
     └──────┬──────┘          └──────┬───────┘
            │                        │
            ▼                        ▼
     ┌──────────────────────────────────────────┐
     │  持久化：MySQL 面试记录 + Redis 用户画像   │
     │  长期记忆：薄弱点追踪（跨会话针对性出题）   │
     └──────────────────────────────────────────┘
```
