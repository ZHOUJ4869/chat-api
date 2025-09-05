# ChatAI 商场客服 · Backend

基于 **Spring Boot 3 + Spring AI (Qwen)** 的电商客服后端。目标：做一个更像真人客服的聊天系统，能记住上下文、准确回答商品问题，并在用户连续发消息时**异步分段回复**（打字机效果）。

## 功能特性
- **记忆与上下文管理**  
  短期记忆（Redis）+ 长期记忆（LMS 摘要）+ 用户画像 + 短期情绪；根据模型最大 token **动态裁剪**要注入的内容，重要信息优先，避免上下文过长。
- **连续消息 & 异步回复**  
  用户可连续发送消息；后端异步生成并**分段返回**，前端通过 `/pull` 拉取展示。
- **RAG 检索增强**  
  先判断是否需要走检索；需要时做**上下文压缩**和**多查询扩展**，再用 **Redis 向量检索**，结合品牌/品类/价格/上架状态等**结构化过滤**；召回不足就**放弃引用**，避免强塞不相关内容。
- **对话安全与体验**  
  启发式规则 + 模型判断做**合规**（沉默/安全提示/改写）；对聊天做**打分/亲密度**，根据分数调整系统提示和语气，更贴近真人客服。

## 技术栈
- **Core**：Spring Boot 3、Spring AI（DashScope/Qwen）
- **Storage/Cache**：MySQL 8、Redis（建议使用 redis/redis-stack 以支持 RediSearch 向量）
- **Build/Run**：Maven 3.9+、JDK 17+
- （可选演进）RabbitMQ/Kafka（消息异步/保序/幂等）

---

## 快速开始

### 1) 环境准备
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
    image: redis/redis-stack:latest   # 带 RediSearch/向量检索
    container_name: redis-stack
    ports:
      - "6379:6379"
      - "8001:8001"   # Redis UI
