# 生产部署与运维手册

## 1. 适用规模与目标

本方案面向 100 人以内律师事务所，典型同时在线 20–40 人，峰值业务请求低于
50 RPS。合同、证据和卷宗属于高敏感资产，因此设计优先级是数据隔离、可恢复性、
审计完整性，其次才是极限吞吐。

建议生产目标：

- 可用性：月度 99.9%。
- 数据库 RPO ≤ 15 分钟，RTO ≤ 2 小时。
- 文件 RPO ≤ 1 小时，RTO ≤ 4 小时。
- 审计日志保存不少于 3 年；卷宗保管期限按律所制度配置。

## 2. 推荐资源

| 层级 | 推荐配置 | 用途 |
|---|---:|---|
| 接入层 | 云负载均衡或 2C/2G Nginx | TLS、限流、WAF、反向代理 |
| 应用层 | 2 台 4C/8G，80GB 系统盘 | 前后端容器；滚动发布 |
| PostgreSQL | 托管版 4C/8G，200GB SSD，高可用 | 核心业务、Flowable、审计 |
| Redis | 托管版 1–2GB，主从 | 8 小时不透明登录会话 |
| 对象存储 | 私有桶，初始 1TB | 合同、证据、卷宗文件 |
| 备份空间 | 独立账号/区域 | 数据库备份与对象版本副本 |

单机试运行可使用 1 台 4C/8G 应用机加托管 PostgreSQL/Redis/对象存储；正式办公
建议至少两台应用节点。应用无本地业务状态，可以横向扩容。

## 3. 网络与安全域

```text
钉钉客户端
   │ HTTPS
WAF / 负载均衡
   │ 仅 443
应用节点 A/B ───── Redis（私网）
   │         └──── PostgreSQL（私网、TLS）
   └────────────── 私有对象存储（短时签名 URL）
```

- 数据库、Redis 和对象存储管理端口不得暴露公网。
- Redis 使用独立 ACL 用户、强密码和 TLS；应用通过 `REDIS_USERNAME`、
  `REDIS_PASSWORD`、`REDIS_SSL_ENABLED=true` 连接。
- 对象桶保持私有；上传链接 15 分钟、下载链接 5 分钟失效。
- 钉钉应用回调域名使用 OA 的 HTTPS 域名，生产禁用 `X-Dev-User`。
- 数据库账号只授予当前 schema 的 DML/DDL 权限；对象存储账号只允许指定桶。
- 密钥放入云密钥管理或主机 secret 文件，禁止提交到 Git。
- 安全组仅允许负载均衡访问应用端口、应用访问数据组件。

## 4. 上线步骤

1. 申请 OA 域名与 TLS 证书，创建钉钉企业内部应用，将登录回调地址配置为
   `https://oa.example.com/auth/dingtalk/callback`。
2. 建立 PostgreSQL、Redis 和私有对象桶，开启数据库 PITR 与对象版本控制。
3. 从 `.env.production.example` 生成生产 secret；`STORAGE_PUBLIC_ENDPOINT` 必须是
   浏览器可访问的对象存储域名，`DINGTALK_REDIRECT_URI` 必须与钉钉开发者后台登记的
  完整回调地址一致。
4. 在发布环境执行：

   ```bash
   ./scripts/production-readiness-check.sh
   docker compose --env-file .env.production \
     -f docker-compose.prod.yml build
   docker compose --env-file .env.production \
     -f docker-compose.prod.yml up -d
   ```

   `production-readiness-check.sh` 会使用全新隔离数据库演练正式配置：核对正式
   Flyway 迁移、确认没有演示账号、确认开发身份头被拒绝，并验证占位密钥会使应用
   安全失败。它不会连接或修改生产数据库。

5. 等待 `/actuator/health/readiness` 返回 `UP`。生产配置校验器会拒绝开发认证、
   占位密钥、非 PostgreSQL 数据库、非 HTTPS 登录回调和浏览器对象存储地址。
   Flyway 只执行正式目录
   `db/migration`，不会载入 `db/dev` 演示数据。
6. 由负载均衡把 HTTPS 请求转发到 `127.0.0.1:8080`，验证钉钉免登、文件上传、
   文件下载、审批和审计日志。
7. 先导入 5–10 名试点人员，运行一周后再同步全员。

边缘 TLS 和双层限流参考 `deploy/edge-nginx.conf.example`；应用桶最小权限参考
`deploy/object-storage-policy.json`。两者必须按正式域名、证书路径和桶名审阅后使用。

## 5. 对象存储 CORS

浏览器直接 PUT 文件时，对象存储需允许 OA 域名：

- Origin：仅 `https://oa.example.com`
- Methods：`PUT, GET, HEAD`
- Headers：`Content-Type, x-amz-*`
- Expose Headers：`ETag`
- Max Age：3600 秒

不要使用 `*` 作为生产 Origin。桶策略仍应为私有，CORS 不等同于访问授权。

## 6. 备份与恢复

### PostgreSQL

- 每日全量备份，WAL 连续归档，保留 35 天。
- 每周复制一份到独立账号或异地存储。
- 每季度在隔离环境执行恢复演练，记录恢复点、耗时和校验结果。
- 恢复后核对 Flyway 版本、核心表数量、最近审计记录和文件元数据抽样。

### 对象文件

- 开启版本控制、服务端加密和 90 天误删保护。
- 使用跨账号或跨区域复制；备份账号禁止应用写入。
- 数据库恢复点与对象版本时间应对齐；孤儿对象可以离线扫描，不能自动删除。

### Redis

Redis 只存登录会话，可重建；启用持久化用于降低故障影响，但不作为核心数据备份。

### 本地演练脚本

开发环境可执行：

```bash
scripts/backup-local.sh
scripts/verify-backup.sh /absolute/path/to/law_firm_oa/backups/<timestamp>
```

第一条命令生成 PostgreSQL custom dump、MinIO 文件数据包及 SHA-256 清单；第二条命令
先校验摘要和归档结构，再创建名称唯一的隔离数据库完成恢复，核对 Flyway、用户、文档、
审计表后自动删除临时数据库。该脚本用于验证恢复流程，不替代生产 PITR、跨区域对象复制
和云厂商备份策略。

生产导出的备份目录可使用下列命令做 AES-256 对称加密、解密校验和滚动保留：

```bash
BACKUP_ENCRYPTION_KEY_FILE=/secure/law-oa-backup.key \
BACKUP_RETENTION_DAYS=35 \
scripts/encrypt-and-retain-backup.sh /absolute/path/to/law_firm_oa/backups/<timestamp>
```

密钥文件必须是普通文件且权限为 `400` 或 `600`，不得与备份位于同一账号或存储区。
备份脚本可通过 `BACKUP_METRICS_FILE` 输出 Prometheus textfile 指标。

## 7. 监控和告警

至少采集：

- 应用：5xx 比例、P95 延迟、JVM 内存、线程、连接池、容器重启次数。
- 数据库：CPU、磁盘、连接数、锁等待、慢查询、复制延迟、备份状态。
- 对象存储：4xx/5xx、容量增长、上传失败、版本复制延迟。
- 业务：登录失败、权限拒绝激增、下载量异常、逾期任务、流程堆积、
  `outbox_events.status=DEAD`、组织同步失败。
- OA 指标：`law_oa_outbox_*`、`law_oa_documents_scan_queue`、
  `law_oa_notifications_unread`、`law_oa_security_events_open_high`、
  `law_oa_job_duration_*` 和 `law_oa_backup_last_*`。

建议告警：健康检查连续 3 次失败、5xx 超过 2%、数据库磁盘超过 75%、备份失败，
以及 `document_security_events` 出现 `HIGH/OPEN`。应用现已在单用户 5 分钟第 10 次
下载时预警、已有 30 次成功下载时拒绝并记录高危事件。

日志不得记录钉钉凭证、会话令牌、文件签名 URL 或文档正文。生产日志保留 30–90
天，审计日志保留在数据库并定期导出到不可变存储。

Redis 中的登录会话键只保存令牌的 SHA-256 摘要，不能保存浏览器携带的原始 bearer
token。前端生产 Nginx 仅转发 readiness/liveness 健康检查，其余 Actuator 路径返回
404，并启用 HSTS、CSP、禁止嵌入、MIME 嗅探保护和最小浏览器权限策略。

## 8. 发布与回滚

- 每次发布先执行后端单元测试、前端构建和 `node scripts/smoke-test.mjs`。
- 每次发布对最终运行镜像执行 OS 高危/严重漏洞扫描，并对后端 fat JAR、前端
  `package-lock.json` 执行依赖漏洞扫描；高危或严重问题未修复不得上线。
- 构建必须使用锁文件安装前端依赖（`npm ci`），镜像必须固定明确版本或 digest，
  发布产物不能在上线后临时安装依赖。
- 数据库迁移只允许向前兼容；先加字段/表，再发代码，下一版本再删除旧结构。
- 应用镜像按版本号和 Git commit 双标签，不复用 `latest` 作为回滚依据。
- 回滚应用前确认新迁移是否向后兼容；禁止直接回滚数据库文件。
- 高风险操作（外部分享、批量导出、删除、保管期限调整）保持审批和审计。

Outbox 补偿前先核对 `event_type`、`aggregate_id` 和 `last_error`。修复根因后将单条
`DEAD` 事件改为 `RETRY` 并把 `available_at` 设置为当前时间；不要批量重放未知事件。

## 9. 月度运维检查

- 验证数据库备份可恢复、对象版本复制正常。
- 复核离职人员、案件成员、下载权限和钉钉应用管理员。
- 抽查审计日志完整性和异常下载告警。
- 检查证书、域名、磁盘、依赖安全更新和过期流程。
- 导出容量趋势；超过 70% 时提前扩容。

## 10. 上线前放行条件

只有以下条件全部完成才可以从“有条件通过”转为“正式放行”：

- 钉钉正式应用的 Client ID、Client Secret、HTTPS 回调和组织账号映射实测成功。
- 正式域名、TLS、WAF/负载均衡、安全组和可信代理头配置通过检查。
- 托管 PostgreSQL 的 TLS、自动备份、PITR 和隔离恢复演练完成。
- 私有对象桶的加密、版本控制、生命周期、CORS 和异地/跨账号复制完成。
- Redis 启用认证与 TLS；所有密钥进入密钥管理服务并完成轮换演练。
- 接入日志、指标、错误和业务告警；值班人收到一条测试告警并确认。
- 完成试点数据导入、关键用户验收、权限抽查和回滚演练。

开发 Compose 内的 MinIO 只用于本地测试，控制台端口 `9001` 不进入生产公网。生产
应使用受支持的私有 S3 兼容对象存储服务，并由 OA 通过私网访问。
