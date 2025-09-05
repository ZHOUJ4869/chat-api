# ChatAI 商场客服 · Backend

基于 **Spring Boot 3 + Spring AI (Qwen)** 的电商客服后端。目标：做一个更像真人客服的聊天系统，能记住上下文、准确回答商品问题，并在用户连续发消息时 **异步分段回复**（打字机效果）。

<p align="left">
  <img src="https://img.shields.io/badge/JDK-17+-blue" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen" />
  <img src="https://img.shields.io/badge/Redis-RediSearch%2FVector-orange" />
  <img src="https://img.shields.io/badge/DB-MySQL%208-informational" />
  <img src="https://img.shields.io/badge/License-MIT-lightgrey" />
</p>


---

## ✨ 核心特性

1. **记忆与上下文管理**  
   - 短期记忆（Redis）+ 长期记忆（LMS 摘要）+ 用户画像 + 短期情绪  
   - 根据模型最大 token **动态裁剪注入长度**（重要信息优先），避免上下文过长带来的报错与成本上升

2. **连续消息 & 异步回复**  
   - 用户可连续发送消息；后端 **异步生成** 并 **分段返回**，前端通过 `/pull` 拉取展示  
   - 规划升级为 **MQ**（Outbox、幂等、按 userId 保序、DLQ/延迟退避），高并发下更稳、更有序

3. **RAG 检索增强（智能判断是否走检索）**  
   - 先判断是否需要查知识/商品库（不相关就不走检索）  
   - 需要时做 **上下文压缩** 与 **多查询扩展**，再用 **Redis 向量检索**，结合 **品牌/品类/价格/上架状态** 等结构化过滤，合并去重取 TopK  
   - **召回不足自动放弃 RAG**，避免“强塞”不相关证据

4. **对话安全与体验**  
   - 启发式规则 + LLM **合规判断**（沉默/安全提示/改写）  
   - **聊天打分/亲密度** 影响系统提示与语气；越界会降分并进入 **冷却**

---

## 🧰 技术栈

- **Core**：Spring Boot 3、Spring AI（DashScope/Qwen）
- **Storage/Cache**：MySQL 8、Redis（建议 `redis/redis-stack`，启用 RediSearch/向量）
- **Build/Run**：Maven 3.9+、JDK 17+
- **可选演进**：RabbitMQ/Kafka（异步/保序/幂等）

---

## 🏗️ 架构概览

```
User
  │
  ▼
[Controller /api/chat/*]
  ├─ Guard(合规：启发式+LLM)
  ├─ Memory(短期/长期摘要/画像/情绪) + Budget(动态裁剪)
  ├─ Router(是否走RAG)
  │    └─ Compress + MultiQueryExpand + VectorSearch + Filter + TopK
  ├─ Prompt Build (系统提示/历史/画像/情绪/RAG)
  ├─ LLM 生成（Qwen）
  ├─ PendingReplyBus(分段写入)
  └─ Persist(消息入库) + LMS 摘要/亲密度更新(异步)
Front-end 轮询 /pull 读取分段结果
```

---

## 🚀 快速开始

### 1) 准备环境

- JDK 17+、Maven 3.9+
- MySQL 8（创建数据库，如 `chat_ai`）
- Redis（推荐 `redis/redis-stack` 镜像以启用 RediSearch）

可选：用 Docker 一键拉起 MySQL/Redis（示例）：

```yaml
# docker-compose.yml
services:
  mysql:
    image: mysql:8
    container_name: mysql8
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: chat_ai
    ports: ["3306:3306"]
    command: ["--default-authentication-plugin=caching_sha2_password"]

  redis:
    image: redis/redis-stack:latest
    container_name: redis-stack
    ports:
      - "6379:6379"
      - "8001:8001"   # RedisInsight UI
```

### 2) 配置（环境变量 / `application.yml`）

**环境变量（推荐）**

```bash
# 模型
export DASHSCOPE_API_KEY=your_dashscope_key

# 数据库
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/chat_ai?useSSL=false&serverTimezone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=root

# Redis
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=
```

**应用配置示例**

```yaml
# src/main/resources/application.yml
server:
  port: 8080

spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD:}
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}

chat:
  memory:
    short-history-max-turns: 12     # 短期记忆轮次
    lms-summary-max-blocks: 4       # 长期摘要块数
  rag:
    expand-num: 3                   # 多查询扩展条数
    per-query-topk: 4
    sim-threshold: 0.5
    final-topk: 6
  async:
    pull-max-chunks: 20             # /pull 每次最多返回的分段数量
```

> **安全提示**：不要把 API Key 提交到仓库；请用环境变量或密钥管理。

### 3) 初始化数据库

- 执行仓库内的 `schema.sql` 或 Flyway/Liquibase（如有）
- 至少包含：用户/消息/摘要/画像等表  
- （可选）Outbox 表（用于后续引入 MQ）

### 4) 构建 & 运行

```bash
mvn -U -DskipTests package
java -jar target/*.jar
# 或：mvn spring-boot:run
```

---

## 🔌 API 说明（简要）

### 1）发送消息（触发异步生成）

```
POST /api/chat/model
Content-Type: application/json
```

**请求示例**

```json
{
  "chatId": "u:1001:a:1",
  "userId": 1001,
  "text": "帮我推荐一款2000-3000的索尼降噪耳机",
  "model": "qwen-plus"
}
```

**说明**

- 服务端会做合规、记忆组合与（必要时）RAG 检索
- 结果将分段写入待返回队列，供 `/pull` 拉取

---

### 2）拉取分段回复（打字机效果）

```
GET /api/chat/pull?chatId={id}&cursor={offset}&max={N}
```

**返回示例**

```json
{
  "cursor": 15,
  "chunks": [
    {"type":"text","content":"您好，这里是客服小助手，"},
    {"type":"text","content":"我给您挑了3款索尼降噪耳机，价格在2000-3000元："},
    {"type":"ref","content":"WH-1000XM4 ..."}
  ],
  "done": false
}
```

---

### 3）模型列表

```
GET /api/chat/models
```

### 4）健康检查

```
GET /actuator/health
```

> 其他接口（会话历史、RAG 调试等）以仓库实际为准。

---

## 🧠 记忆与上下文（怎么“像真人”）

- **短期记忆（Redis）**：仅保留最近 N 轮（USER/ASSISTANT 两类），高保真；  
- **长期记忆（LMS 摘要）**：当对话累计到一定长度时异步生成摘要块（512–768 tokens），只保留最近 3–5 块；  
- **用户画像 + 短期情绪**：从最近发言中抽取预算/偏好/禁忌；情绪有效期 10–20 分钟，影响语气；  
- **动态预算**：按模型最大 token 计算注入长度，优先级（示例）：历史 > RAG > 画像 > 情绪 > 摘要，必要时裁剪。

---

## 🔎 RAG（检索增强）工作方式

- **是否走检索**：先做路由判断（商品相关才走 RAG）；  
- **检索流程**：上下文压缩 → 多查询扩展 → Redis 向量检索（相似度阈值） → 结构化过滤（brand/category/price/isActive） → 去重排序 TopK；  
- **放弃策略**：召回不足/分数低/关键信息缺失 → **放弃注入**，避免“强行拼接资料”。

**索引文档的元数据建议**

```json
{
  "text": "产品介绍/问答片段...",
  "metadata": {
    "productId": "p123",
    "brand": "sony",
    "category": "headphone",
    "price": 2199,
    "isActive": true,
    "url": "https://example.com/item/p123"
  }
}
```

---

## 📦 连续消息 & 异步回复

- **当前实现**：定时线程池 + Redis 队列，LLM 生成结果按段写入 `PendingReplyBus`，前端 `/pull` 拉取展示；  
- **演进方向（可选）**：引入 MQ（RabbitMQ/Kafka）+ Outbox，**按 userId 保序**、**幂等**、**失败走 DLQ/延迟退避**，提升高并发稳定性。

---

## 🔐 合规与体验细节

- **合规**：启发式规则（关键词/正则）做初筛，LLM 复核，动作包括：沉默 / 安全提示 / 安全改写 / 放行；  
- **聊天打分/亲密度**：随互动增长，越界会扣分并进入冷却；分数影响系统提示与语气（正式/亲和）。

---

## 📁 目录结构（示例）

```
src/
 ├─ main/java/com/yourorg/chat/
 │   ├─ controller/        # ChatController (/api/chat/*)
 │   ├─ rag/               # RagQueryPipeline、AllowedDictService
 │   ├─ memory/            # Redis 短期记忆、LMS 摘要
 │   ├─ guard/             # 启发式+LLM 合规
 │   ├─ async/             # PendingReplyBus / 定时任务 /（规划 MQ）
 │   ├─ config/            # Spring AI、Redis、Async、WebConfig
 │   └─ ...                # service/mapper/domain 等
 └─ resources/
     ├─ application.yml
     └─ mapper/*.xml
```

---

## 🧪 本地调试建议

- **Postman/Thunder Client**：先调 `POST /api/chat/model`，再轮询 `GET /api/chat/pull`  
- **健康检查**：`GET /actuator/health`  
- **Redis UI**：访问 `http://localhost:8001`（若用 redis-stack）

---

## 🗺️ Roadmap（可选）

- [ ] 路由器输出改为 **JSON Schema/函数调用** 强约束  
- [ ] 引入 **MQ**：Outbox、幂等、按 userId 保序、DLQ/延迟退避  
- [ ] RAG 质量闭环：用户反馈 → 文档粒度打点 → 低质剪枝/同义词热更新  
- [ ] 更多产品类目/品牌字典与别名

---

## ❓FAQ

- **Q：一定需要 RediSearch 吗？**  
  A：是。RAG 的向量检索依赖 RediSearch（推荐用 `redis/redis-stack`）。

- **Q：为什么分段返回？**  
  A：模拟“打字机”效果，体验更顺滑；同时便于高并发下的超时与重试处理。

- **Q：没有 MQ 也能跑吗？**  
  A：可以。当前默认走“定时任务 + Redis”的异步链路，后续可无缝迁移到 MQ。

---

## 📜 许可证

本项目采用 MIT 许可证（或根据你的实际选择修改）。

---

## 🤝 贡献

欢迎提交 Issue/PR：  

- 代码风格：遵循项目现有格式  
- 新功能请附简单说明与示例请求/响应  
- 涉及到配置/密钥的变动，请注明安全注意事项
