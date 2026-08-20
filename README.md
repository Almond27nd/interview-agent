# InterviewAgent · AI 模拟面试系统

基于 **Java 21 + Spring Boot 3.4 + [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba)** 构建的 AI 模拟面试系统。用户上传简历、输入目标岗位 JD，系统自动完成简历匹配、智能出题、多轮技术面试、实时评分，并在面试结束后生成评估报告与个性化复习计划。

涵盖多 Agent 协作编排、RAG 多路召回、动态难度调节、Agent 记忆系统等大模型应用核心能力，底层大模型统一使用通义千问 DashScope。

## 核心特性

- **多 Agent 协作**：7 个专职 Agent（聊天 / JD 分析 / 简历匹配 / 出题规划 / 面试官 / 评估 / 复习规划）通过 Spring AI Alibaba Graph 的 StateGraph 编排成有向图
- **RAG 多路召回**：Milvus 向量检索 + 自研内存 BM25 关键词检索双路并行，去重合并 + LLM 全量重排
- **RAG 离线评估**：基于人工标注数据集计算 Recall@K / MRR，支撑参数 A/B 对比
- **动态难度调节**：候选题池按难度分档，面试时自适应取题，连对升档 / 连错降档
- **Agent 记忆系统**：短期对话记忆（滑动窗口）+ 长期用户画像与薄弱点追踪，Redis 缓存 + MySQL 持久化
- **Skill 技能系统**：4 个内置 Skill（快速测验 / 概念教学 / 项目亮点 / 技术对比），可插拔扩展
- **工具集成**：GitHub 项目搜索、网页抓取，复习规划 Agent 的 ReactAgent 自主调用
- **WebSocket 实时通信**：面试编排在独立线程池执行，「人在环」逐题问答，阶段进展实时推送

## 系统架构

```
用户（浏览器）
      │
      ▼
前端 interview-agent-web  (React + Vite, :5173)
      │  /api、/ws 代理
      ▼
后端 Spring Boot  (:9090, WebSocket 实时通信)
      │
      ▼
Agent 编排层（StateGraph DAG 串联）
  JD 分析 → 简历匹配 → 历史薄弱点召回 → 出题规划（两阶段）
                                    ↓
                  面试官（多轮问答 + 动态难度调节 + 追问）
                                    ↓
                       评估报告 → 复习规划（ReactAgent）
      │
      ▼
基础能力层
  RAG 多路召回        记忆系统             Skill / 工具
  Milvus + BM25      短期滑窗 + 长期画像    4 个内置 Skill
  去重合并 + LLM 重排  Redis + MySQL        GitHub / 网页抓取
      │
      ▼
基础设施层（Docker Compose 一键启动）
  Milvus    Redis    MySQL
```

## 技术栈

| 类别 | 选型 | 用途 |
|------|------|------|
| 语言 | Java 21 | 主语言 |
| 应用框架 | Spring Boot 3.4.1 | Web / WebSocket / 依赖注入 / 数据访问 |
| AI 框架 | Spring AI Alibaba | Agent 编排 / 工具调用 / RAG |
| 大模型 | 通义千问 DashScope | LLM 推理（qwen-plus） |
| Embedding | text-embedding-v3 | 文本向量化（1024 维） |
| 向量数据库 | Milvus 2.4 | 向量存储与 COSINE 检索 |
| 缓存 | Redis 7 | 会话缓存 / 用户画像缓存 |
| 持久化 | MySQL 8.0 | 用户 / 面试记录 / 评估报告 |
| 认证 | Spring Security + JWT | 登录鉴权 |
| 容器化 | Docker Compose | 一键部署基础设施 |
| 前端 | React 19 + Vite + TypeScript | Web 交互界面 |

## 项目结构

```
interview-agent/
├── interview-agent-java/          # 后端（Spring Boot + Spring AI Alibaba）
│   ├── src/main/java/com/interview/agent/
│   │   ├── agent/                 # Agent 实现（7 个专职 Agent）
│   │   ├── rag/                   # RAG 多路召回 + 离线评估
│   │   ├── memory/                # 记忆系统（短期 + 长期）
│   │   ├── graph/                 # StateGraph 编排
│   │   ├── skill/                 # Skill 技能系统
│   │   ├── mcp/                   # 工具集成（GitHub / 网页抓取）
│   │   ├── loader/                # 文档加载与题库解析
│   │   ├── auth/                  # JWT 认证
│   │   ├── handler/               # WebSocket / 健康检查
│   │   ├── config/                # 配置
│   │   └── model/                 # 数据模型
│   ├── data/questions/            # 内置面试题库
│   ├── docker-compose.yml         # 基础设施编排
│   ├── Dockerfile                 # 后端镜像构建
│   └── pom.xml
├── interview-agent-web/           # 前端（React + Vite + TypeScript）
│   ├── src/
│   │   ├── components/            # UI 组件
│   │   ├── hooks/                 # WebSocket 连接管理
│   │   ├── api/                   # API 客户端
│   │   ├── store/                 # 状态管理（Zustand）
│   │   └── types/                 # 类型定义
│   └── package.json
└── README.md                      # 本文件
```

## 快速开始

### 环境准备

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | **21** | 必须 JDK 21 |
| Maven | 3.9+ | 构建后端 |
| Docker Desktop | 最新 | 跑基础设施 |
| Node.js | 18+ | 跑前端 |
| 通义千问 API Key | — | 见下方 |

**获取通义千问 API Key**：打开 [DashScope 控制台](https://dashscope.console.aliyun.com/) → 登录 → 「API-KEY 管理」→ 创建新 Key（以 `sk-` 开头）。新用户有免费额度。

### 启动步骤

```bash
# 1. 配置环境变量
cd interview-agent-java
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY=sk-你的真实key

# 2. 启动基础设施（Milvus + Redis + MySQL）
make infra-up

# 3. 启动后端
make run

# 4. 启动前端
cd ../interview-agent-web
npm install
npm run dev
```

访问 http://localhost:5173 即可使用。

### 使用流程

1. **注册 / 登录**
2. **上传题库**（可选）：上传 PDF / TXT / MD 格式的面试题库，系统自动解析向量化。仓库自带 Go / MySQL / 分布式 / 消息队列 / Redis 五个方向题库
3. **开始面试**：填入 JD 和简历，系统自动执行完整面试流程
4. **查看结果**：评估报告与复习计划持久化在 MySQL，可随时查看

## 面试流程

```
输入 JD + 简历
      │
      ▼
JD 分析 → 简历匹配 → 读取历史薄弱点
                        │
                        ▼
              出题规划（两阶段）
              Phase1: 规划方向 + 分档题池  ← Milvus 向量检索
              Phase2: RAG 检索 / LLM 出题  ← BM25 关键词检索
                        │                    去重合并 + LLM 全量重排
                        ▼
                   面试官（多轮问答 + 追问）
                   ← 动态难度调节（连对升 / 连错降）
                   ← 短期记忆 + 候选人画像
                        │
                  ┌───────┴────────┐
                  ▼                ▼
            评估报告          复习规划 ← ReactAgent + GitHub 工具
                  │                │
                  ▼                ▼
          持久化：MySQL 面试记录 + Redis 用户画像
          长期记忆：薄弱点追踪（跨会话针对性出题）
```

## 常用命令

```bash
# 后端
make run            # 启动后端
make build          # 编译
make test           # 运行测试
make infra-up       # 启动基础设施
make infra-down     # 停止基础设施
make infra-status   # 查看容器状态
make docker-build   # 构建后端镜像

# 前端
npm run dev         # 启动开发服务器（:5173）
npm run build       # 生产构建
npm run lint        # ESLint 检查
```

## 子项目文档

- [后端文档](interview-agent-java/README.md) — 架构详解、快速开始、FAQ
- [前端文档](interview-agent-web/README.md) — 前端结构、启动、FAQ

## License

[MIT](LICENSE)
