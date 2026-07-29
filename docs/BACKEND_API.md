# 后端 API 与一致性约定

## 1. 通用约定

- API 前缀：`/api`
- 正式环境认证：`Authorization: Bearer <opaque-session-token>`
- 开发环境认证：`X-Dev-User: admin`
- 写接口建议携带：`Idempotency-Key: <调用方生成的唯一键>`
- 每个响应携带：`X-Correlation-Id`
- 错误结构：`code`、`message`、`timestamp`、`fieldErrors`

幂等键在同一组织和操作范围内保留 24 小时。相同请求重放会返回首次结果；同一个键
用于不同参数时返回 `409 IDEMPOTENCY_KEY_REUSED`。未完成的并发请求返回
`409 IDEMPOTENCY_IN_PROGRESS`。

## 2. 统一审批中心

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/workflows` | 发起案件、合同、用印、请假或报销审批 |
| `GET` | `/workflows/tasks` | 当前登录人实时 Flowable 待办 |
| `GET` | `/workflows/inbox?status=PENDING` | 本地审批中心；可查 `APPROVED`、`REJECTED`、`TRANSFERRED` |
| `POST` | `/workflows/tasks/{taskId}/complete` | `decision=APPROVE/REJECT` |
| `POST` | `/workflows/tasks/{taskId}/transfer` | 转交给本组织有效用户 |
| `POST` | `/workflows/tasks/{taskId}/remind` | 发起人或审批管理员催办 |

支持的业务类型：

| 类型 | 提交状态 | 通过状态 | 驳回状态 |
|---|---|---|---|
| `MATTER` | `CONFLICT_REVIEW` | `ACTIVE` | `REJECTED` |
| `CONTRACT` | `REVIEWING` | `APPROVED` | `REJECTED` |
| `SEAL_REQUEST` | `SUBMITTED` | `APPROVED` | `REJECTED` |
| `LEAVE_REQUEST` | `SUBMITTED` | `APPROVED` | `REJECTED` |
| `EXPENSE_CLAIM` | `SUBMITTED` | `APPROVED` | `REJECTED` |

审批完成、业务状态变化、动作日志和 Outbox 事件处于同一数据库事务中。驳回会终止
Flowable 实例并取消剩余待办，不会留下“流程结束但业务仍在审核中”的悬挂状态。

## 3. 国际化租户、办公室与跨境案件

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/public/tenant-config` | 无需登录读取双语品牌、支持语言、总部时区和基础币种 |
| `GET` | `/offices` | 全所管理员返回全部办公室；普通成员只返回已授权办公室 |
| `GET` | `/offices/{officeId}/members` | 全所管理员或该办公室管理员查询有效成员 |
| `PUT` | `/offices/{officeId}/members/{userId}` | 加入/调整成员；支持 `accessLevel`、`primary`、`validUntil` |
| `DELETE` | `/offices/{officeId}/members/{userId}` | 撤销非主办公室授权 |
| `GET` | `/me` | 额外返回 `globalOfficeAccess` 和 `accessibleOffices[].manageable` |
| `POST` | `/matters` | 可提交 `officeId`、`countryCode`、`jurisdiction`、`workingLanguage`、`billingCurrency` |

创建案件时未指定办公室，会继承当前操作人的主办公室；国家和结算币种默认继承办公室。
支持的工作语言为 `zh-CN`、`en-US` 和 `ar`。普通用户只能选择已授权办公室，并只能
指定该办公室成员为承办人；全所管理员可显式跨办公室指派，案件成员关系随后成为访问边界。

办公室管理员只能在自己管理的办公室授予 `MEMBER`；`MANAGER`、主办公室调整只能由
全所管理员或管理合伙人执行。主办公室不能设置到期时间，也不能直接撤销；应先设置新的
主办公室。所有加入、调整和撤销动作同时写入 `office_membership_history` 和通用审计日志。

## 4. 常见 OA 模块

| 模块 | 主要端点 | 关键约束 |
|---|---|---|
| 公告 | `GET/POST /announcements`、`POST /{id}/publish`、`POST /{id}/read` | 可提交 `officeId`；空值仅允许全所管理员发布全所公告 |
| 协作任务 | `GET/POST /work-tasks`、`PATCH /{id}/status`、`POST /{id}/comments` | 创建人/负责人/参与人可见，取消仅创建人 |
| 请假 | `GET/POST /leave-requests`、`POST /{id}/submit` | 申请人数据范围、两级审批、有效状态时间段不可重叠 |
| 报销 | `GET/POST /expense-claims`、`POST /{id}/submit`、`POST /{id}/pay` | 服务端汇总明细、两级审批、财务付款权限 |
| 会议室 | `/meetings/rooms`、`/meetings/bookings`、`POST /bookings/{id}/cancel` | 按办公室过滤，容量校验，同房间有效预约时段不可重叠 |
| 通讯录 | `GET /organization/departments`、`GET /organization/directory` | 按当前用户办公室范围过滤，联系方式按源数据脱敏 |

请假和会议室的时间冲突由 PostgreSQL `EXCLUDE USING gist` 约束保证，因此即使两个请求
并发到达，也不会出现重复占用。

## 5. 通知中心

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/notifications?status=UNREAD&page=1&size=20` | 分页查询本人通知 |
| `GET` | `/notifications/unread-count` | 未读计数 |
| `PATCH` | `/notifications/{id}/read` | 标记本人通知已读 |

通知先写入 `outbox_events`，后台分批投递到 `notification_inbox`。重复事件通过
`recipient_user_id + deduplication_key` 去重。失败采用指数退避，最多重试 8 次；
`DEAD` 事件应接入监控并由管理员人工补偿。

## 6. 组织同步

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/organization/sync` | 写入 `DINGTALK`、`FEISHU` 或 `MANUAL` 快照 |
| `GET` | `/organization/sync/runs` | 最近 100 次同步结果 |
| `GET` | `/organization/users` | 当前组织有效用户 |

同步请求包含部门树和用户快照。外部 `user_id/open_id/union_id` 保存在
`external_identities`，外部部门 ID 保存在 `external_departments`。默认不会停用快照
之外的用户；只有 `mode=FULL` 且显式设置 `deactivateMissingUsers=true` 才会停用同来源
缺失用户。

该接口要求 `ORGANIZATION_SYNC` 权限。当前实现提供稳定的入库合同，后续可将飞书或
钉钉 API 拉取适配器接到同一服务，无需改变案件、文档和审批模块。

## 7. 审计与权限

`GET /audit-logs` 支持按 `action`、`resourceType`、`actorUserId` 过滤并分页，只允许具有
`AUDIT_VIEW` 权限的角色访问。审计记录包含操作人、结果、原因、资源、时间、来源 IP、
User-Agent 和 correlation ID。

后端同时使用角色、权限和业务数据范围：

- 全局管理动作按权限码校验。
- 通讯录、公告、会议室、请假、报销及管理型查询按办公室范围校验。
- 案件/合同/文档按组织和成员关系校验，允许显式的跨办公室案件协作。
- 审批按 Flowable 候选组或任务受让人校验；候选组和全量查询还必须通过业务办公室范围。
- 前端是否显示按钮不参与安全决策。

## 8. 文档上传

1. `POST /documents/uploads` 登记文件元数据和预期 SHA-256。
2. 浏览器使用 15 分钟有效的签名 URL 直接 PUT 到私有对象存储。
3. `POST /documents/uploads/{uploadId}/complete` 校验对象大小并流式计算 SHA-256。
4. 校验通过后创建不可变版本；失败会把上传会话标记为 `FAILED`。
5. 下载前后端再次校验案件/合同成员和 `can_download`，仅返回 5 分钟有效的 URL。
6. 单用户滚动 5 分钟内第 10 次成功下载产生中级预警；已有 30 次成功下载时拒绝新请求，
   返回 `429 DOCUMENT_DOWNLOAD_RATE_LIMITED`。

具有 `DOCUMENT_SECURITY_VIEW` 权限的全所管理员可通过
`GET /document-security/events?status=OPEN&limit=50` 查询下载预警和限流事件。
安全事件包含操作人、文档、版本、时间窗口、下载次数、IP、User-Agent 和关联 ID。

文件正文不进入应用日志或数据库；数据库仅保存对象键、版本、摘要、大小和授权记录。

## 9. 管理员有效权限治理

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/admin/users/{userId}/effective-access` | 实时汇总目标用户的角色、最终权限、权限来源和办公室授权 |

该接口要求 `ADMIN_CONSOLE_VIEW`，并严格限定当前组织；普通用户访问返回 403，不存在或
跨组织用户返回 404。响应中的权限不是缓存快照，而是按当前角色关系实时计算，因此角色
撤权会在后续请求立即生效。办公室授权只把有效期内记录计入活动范围，同时返回账号停用、
无角色、无活动办公室授权、授权已过期和 7 天内到期等风险提示，供管理员在保存变更前
核对实际影响。前端展示这些信息不改变后端逐请求权限校验。
