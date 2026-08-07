# InterviewAgent Web - AI 模拟面试系统前端

InterviewAgent 的 Web 前端，基于 React + TypeScript + Vite 构建。通过 WebSocket 与后端实时通信，提供完整的 AI 模拟面试交互界面。

## 技术栈

| 类别 | 选型 |
|------|------|
| 框架 | React 19 + TypeScript |
| 构建 | Vite 8 |
| 样式 | Tailwind CSS 4 |
| 状态管理 | Zustand |
| 通信 | WebSocket（面试实时交互）+ REST API（登录注册） |

## 前置条件

1. **Node.js 18+**

```bash
node --version   # 确认已安装
```

2. **后端服务已启动**

前端依赖后端提供 API 和 WebSocket 服务（默认 `localhost:9090`）。请先按照 [interview-agent](../interview-agent) 后端项目的说明启动后端：

```bash
cd ../interview-agent
make infra-up              # 启动 Milvus + Redis + MySQL
go run cmd/main.go web     # 启动后端，监听 :9090
```

## 快速启动

```bash
# 1. 安装依赖
npm install

# 2. 启动开发服务器
npm run dev
```

启动后访问 http://localhost:5173

## 使用流程

1. **注册/登录** — 首次使用需注册账号
2. **上传题库**（可选） — 支持上传 PDF/TXT/MD 格式的面试题库，系统自动解析并向量化。不上传也可以面试，由 LLM 直接出题
3. **开始面试** — 输入 JD（URL/文件/文本）和简历，系统自动执行完整面试流程：
   - JD 分析 → 简历匹配 → RAG 题库检索 → 出题规划
   - 逐题面试（实时评分 + 动态难度调节）
   - 评估报告 + 个性化复习规划
4. **日常聊天** — 也可以直接聊技术问题、面试技巧等

## 项目结构

```
src/
├── components/
│   ├── LoginPage.tsx         # 登录/注册页
│   ├── ChatWindow.tsx        # 主聊天界面（面试交互）
│   ├── Sidebar.tsx           # 侧边栏（导航 + 连接状态）
│   ├── MessageBubble.tsx     # 消息气泡
│   ├── StageIndicator.tsx    # 面试阶段指示器
│   ├── ScoreCard.tsx         # 答题评分卡片
│   ├── ReportCard.tsx        # 评估报告展示
│   ├── ReviewPlanCard.tsx    # 复习规划展示
│   └── FileUpload.tsx        # 文件拖拽上传
├── hooks/
│   └── useWebSocket.ts       # WebSocket 连接管理
├── api/
│   ├── ws.ts                 # WebSocket 客户端
│   └── auth.ts               # 登录注册 API
├── store/
│   ├── authStore.ts          # 认证状态（Zustand）
│   └── chatStore.ts          # 聊天/面试状态（Zustand）
├── types/
│   └── message.ts            # 消息类型定义
├── App.tsx                   # 根组件
└── main.tsx                  # 入口
```

## 常用命令

```bash
npm run dev       # 启动开发服务器（默认 :5173）
npm run build     # 生产构建（输出到 dist/）
npm run preview   # 预览生产构建
npm run lint      # ESLint 检查
```

## 后端连接配置

开发模式下，Vite 代理将请求转发到后端：

- `/api/*` → `http://localhost:9090`（REST API）
- `/ws` → `ws://localhost:9090`（WebSocket）

如果后端端口不是 9090，修改 `vite.config.ts` 中的 proxy 配置。

## 常见问题

**Q: 页面白屏？**

检查后端是否已启动。打开浏览器开发者工具（F12）查看 Console 和 Network，如果看到 `ECONNREFUSED` 或 WebSocket 连接失败，说明后端未运行。

**Q: 登录后无法连接？**

确认后端 `go run cmd/main.go web` 已正常启动且输出了 `[Web] 服务器启动: http://localhost:9090`。