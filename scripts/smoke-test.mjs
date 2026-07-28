import { createHash } from 'node:crypto'

const baseUrl = process.env.OA_BASE_URL ?? 'http://localhost:8081/api'
const checks = []

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function api(path, options = {}, user = 'admin') {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'X-Dev-User': user,
      ...(options.headers ?? {}),
    },
  })
  const text = await response.text()
  let body
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = text
  }
  if (!response.ok) {
    const error = new Error(`${options.method ?? 'GET'} ${path} -> ${response.status}: ${text}`)
    error.status = response.status
    error.body = body
    throw error
  }
  return body
}

async function publicApi(path) {
  const response = await fetch(`${baseUrl}${path}`, {
    headers: { 'Accept-Language': 'en-US' },
  })
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  if (!response.ok) throw new Error(`GET ${path} -> ${response.status}: ${text}`)
  return body
}

async function expectApiError(path, options, user, expectedStatus, expectedCode) {
  try {
    await api(path, options, user)
    throw new Error(`${path} unexpectedly succeeded`)
  } catch (error) {
    assert(error.status === expectedStatus, `${path} 应返回 ${expectedStatus}，实际为 ${error.status}`)
    assert(error.body?.code === expectedCode, `${path} 应返回 ${expectedCode}，实际为 ${error.body?.code}`)
    return error.body
  }
}

async function check(name, fn) {
  await fn()
  checks.push(name)
  console.log(`✓ ${name}`)
}

const nonce = Date.now().toString().slice(-8)
const adminId = '00000000-0000-0000-0002-000000000001'
const lawyerId = '00000000-0000-0000-0002-000000000002'
const seededMatterId = '00000000-0000-0000-0006-000000000001'
const seededContractId = '00000000-0000-0000-0007-000000000001'
const seededPartyId = '00000000-0000-0000-0004-000000000002'
const seededSealId = '00000000-0000-0000-0008-000000000001'
const riyadhOfficeId = '00000000-0000-0000-0012-000000000002'
const shanghaiOfficeId = '00000000-0000-0000-0012-000000000008'

let createdParty
let createdClient
let createdMatter
let createdDocument
let createdArchive
let sealRequest
let contractWorkflow

async function approveWorkflow(processInstanceId, expectedSteps = 2) {
  for (let step = 0; step < expectedSteps; step += 1) {
    const tasks = await api('/workflows/tasks')
    const task = tasks.find((item) => item.processInstanceId === processInstanceId)
    assert(task, `审批第 ${step + 1} 级任务不存在`)
    await api(`/workflows/tasks/${task.id}/complete`, {
      method: 'POST',
      headers: { 'Idempotency-Key': `smoke-approve-${task.id}` },
      body: JSON.stringify({
        decision: 'APPROVE',
        comment: `自动化验收第 ${step + 1} 级通过`,
      }),
    })
  }
}

await check('健康检查与开发身份', async () => {
  const me = await api('/me')
  assert(me.username === 'admin', '未以 admin 身份进入系统')
  assert(me.primaryOfficeId && me.timezone && me.defaultCurrency, '当前用户缺少办公室国际化信息')
})

await check('公开双语品牌配置无需登录即可读取', async () => {
  const config = await publicApi('/public/tenant-config')
  assert(config.shortNameZh === '文森' && config.shortNameEn === 'WINSON', '中英文品牌配置不正确')
  assert(config.supportedLocales.includes('zh-CN') && config.supportedLocales.includes('en-US'), '支持语言不完整')
  assert(config.primaryTimezone === 'Asia/Dubai' && config.baseCurrency === 'AED', '总部时区或基础币种不正确')
})

await check('受保护接口拒绝匿名访问', async () => {
  const response = await fetch(`${baseUrl}/offices`)
  assert([401, 403].includes(response.status), `匿名办公室接口应被拒绝，实际为 ${response.status}`)
})

await check('全球办公室、时区和当地币种', async () => {
  const offices = await api('/offices')
  assert(offices.length >= 12, '全球办公室种子数据不完整')
  const dubai = offices.find((office) => office.code === 'DXB')
  const riyadh = offices.find((office) => office.code === 'RUH')
  assert(dubai?.timezone === 'Asia/Dubai' && dubai?.defaultCurrency === 'AED', '迪拜总部配置不正确')
  assert(riyadh?.timezone === 'Asia/Riyadh' && riyadh?.defaultCurrency === 'SAR', '利雅得办公室配置不正确')
})

await check('跨境案件字段校验与英文错误消息', async () => {
  const validation = await expectApiError('/matters', {
    method: 'POST',
    headers: { 'Accept-Language': 'en-US' },
    body: JSON.stringify({
      matterNumber: `INVALID-${nonce}`,
      title: 'Invalid international matter',
      matterType: 'CROSS_BORDER',
      responsibleUserId: adminId,
      countryCode: 'sa',
      workingLanguage: 'fr-FR',
      billingCurrency: 'sar',
      clientIds: [],
      parties: [],
    }),
  }, 'admin', 400, 'VALIDATION_FAILED')
  assert(validation.message === 'Request validation failed', '校验错误未按英文返回')
  assert(validation.fieldErrors.countryCode, '国家代码校验错误缺失')
  assert(validation.fieldErrors.workingLanguage, '工作语言校验错误缺失')
  assert(validation.fieldErrors.billingCurrency, '币种校验错误缺失')

  const officeError = await expectApiError('/matters', {
    method: 'POST',
    headers: { 'Accept-Language': 'en-US' },
    body: JSON.stringify({
      matterNumber: `INVALID-OFFICE-${nonce}`,
      title: 'Invalid office matter',
      matterType: 'CROSS_BORDER',
      responsibleUserId: adminId,
      officeId: 'ffffffff-ffff-ffff-ffff-ffffffffffff',
      clientIds: [],
      parties: [],
    }),
  }, 'admin', 400, 'OFFICE_INVALID')
  assert(
    officeError.message === 'The responsible office does not exist or is inactive',
    '办公室错误未按英文返回',
  )
})

await check('案件未指定办公室时继承承办律师主办公室', async () => {
  const inherited = await api('/matters', {
    method: 'POST',
    body: JSON.stringify({
      matterNumber: `AUTO-OFFICE-${nonce}`,
      title: `办公室继承验收-${nonce}`,
      matterType: 'ADVISORY',
      responsibleUserId: adminId,
      clientIds: [],
      parties: [],
    }),
  })
  assert(inherited.summary.officeNameEn === 'Dubai Headquarters', '未继承管理员的迪拜主办公室')
  assert(inherited.summary.countryCode === 'AE', '未继承办公室国家')
  assert(inherited.summary.billingCurrency === 'AED', '未继承办公室默认币种')
})

await check('组织、案件、合同、期限基础数据', async () => {
  const [users, matters, contracts, deadlines] = await Promise.all([
    api('/organization/users'),
    api('/matters'),
    api('/contracts'),
    api('/deadlines'),
  ])
  assert(users.length >= 3, '组织用户未同步')
  assert(matters.length >= 2, '案件种子数据缺失')
  assert(contracts.length >= 1, '合同种子数据缺失')
  assert(deadlines.length >= 2, '期限种子数据缺失')
})

await check('案件列表按成员范围隔离', async () => {
  const assistantMatters = await api('/matters', {}, 'liassistant')
  assert(
    assistantMatters.length === 1 && assistantMatters[0].id === seededMatterId,
    '助理看到了未加入的案件',
  )
})

await check('主体建档与客户转化', async () => {
  createdParty = await api('/parties', {
    method: 'POST',
    body: JSON.stringify({
      partyType: 'ORGANIZATION',
      displayName: `验收客户-${nonce}`,
      aliases: [`验收别名-${nonce}`],
      notes: '自动化冒烟测试创建，可保留用于验收追踪',
    }),
  })
  createdClient = await api('/clients', {
    method: 'POST',
    body: JSON.stringify({
      partyId: createdParty.id,
      clientNumber: `TEST-CL-${nonce}`,
      ownerUserId: adminId,
      source: 'SMOKE_TEST',
    }),
  })
  assert(createdClient.partyId === createdParty.id, '客户未关联新主体')
  const updatedParty = await api(`/parties/${createdParty.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      partyType: 'ORGANIZATION',
      displayName: `验收客户更新-${nonce}`,
      aliases: [`验收别名更新-${nonce}`],
      notes: '验证主体编辑、别名替换与列表刷新',
    }),
  })
  assert(updatedParty.displayName.includes('更新'), '主体编辑未生效')
  const updatedClient = await api(`/clients/${createdClient.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      partyId: createdParty.id,
      clientNumber: `TEST-CL-${nonce}`,
      ownerUserId: adminId,
      source: 'SMOKE_TEST_UPDATED',
    }),
  })
  assert(updatedClient.id === createdClient.id, '客户编辑未保持原记录')
})

await check('利益冲突检索', async () => {
  const conflict = await api('/conflict-checks/preview', {
    method: 'POST',
    body: JSON.stringify({
      matterTitle: `验收争议-${nonce}`,
      partyIds: [seededPartyId],
    }),
  })
  assert(conflict.hitCount >= 1, '已有关联案件的对方主体未命中冲突')
})

await check('案件立案与承办成员', async () => {
  createdMatter = await api('/matters', {
    method: 'POST',
    body: JSON.stringify({
      matterNumber: `TEST-ML-${nonce}`,
      title: `自动化验收案件-${nonce}`,
      matterType: '专项法律服务',
      responsibleUserId: adminId,
      openedAt: new Date().toISOString().slice(0, 10),
      description: '端到端验收案件',
      officeId: riyadhOfficeId,
      countryCode: 'SA',
      jurisdiction: 'Kingdom of Saudi Arabia / 沙特阿拉伯',
      workingLanguage: 'en-US',
      billingCurrency: 'SAR',
      clientIds: [createdClient.id],
      parties: [{
        partyId: createdParty.id,
        partyRole: '委托人',
        side: 'CLIENT',
      }],
    }),
  })
  assert(createdMatter.summary.status === 'CONFLICT_REVIEW', '新案件状态不正确')
  assert(createdMatter.summary.memberCount === 1, '承办成员未建立')
  assert(createdMatter.summary.officeId === riyadhOfficeId, '案件未关联利雅得办公室')
  assert(createdMatter.summary.countryCode === 'SA', '案件司法辖区国家不正确')
  assert(createdMatter.summary.workingLanguage === 'en-US', '案件工作语言未保存')
  assert(createdMatter.summary.billingCurrency === 'SAR', '案件结算币种未保存')
})

await check('案件检索、编辑、生命周期与写权限', async () => {
  createdMatter = await api(`/matters/${createdMatter.summary.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      title: `自动化验收案件更新-${nonce}`,
      matterType: '专项法律服务',
      responsibleUserId: adminId,
      openedAt: new Date().toISOString().slice(0, 10),
      courtName: 'Riyadh Commercial Court',
      caseNumber: `RC-${nonce}`,
      description: '案件资料编辑和生命周期验收',
      officeId: riyadhOfficeId,
      countryCode: 'SA',
      jurisdiction: 'Kingdom of Saudi Arabia / 沙特阿拉伯',
      workingLanguage: 'en-US',
      billingCurrency: 'SAR',
    }),
  })
  assert(createdMatter.courtName === 'Riyadh Commercial Court', '案件编辑未生效')
  const filtered = await api(`/matters?status=CONFLICT_REVIEW&query=${encodeURIComponent(`RC-${nonce}`)}`)
  assert(filtered.some((item) => item.id === createdMatter.summary.id), '案件状态筛选或服务端检索未生效')

  const lifecycleMatter = await api('/matters', {
    method: 'POST',
    body: JSON.stringify({
      matterNumber: `TEST-LC-${nonce}`,
      title: `生命周期验收案件-${nonce}`,
      matterType: 'ADVISORY',
      responsibleUserId: adminId,
      officeId: riyadhOfficeId,
      countryCode: 'SA',
      workingLanguage: 'en-US',
      billingCurrency: 'SAR',
      clientIds: [],
      parties: [],
    }),
  })
  const active = await api(`/matters/${lifecycleMatter.summary.id}/lifecycle`, {
    method: 'POST',
    body: JSON.stringify({ targetStatus: 'ACTIVE', reason: '冲突检索完成，确认接受委托' }),
  })
  assert(active.summary.status === 'ACTIVE', '案件确认立案未进入 ACTIVE')
  await expectApiError(`/matters/${lifecycleMatter.summary.id}/lifecycle`, {
    method: 'POST',
    body: JSON.stringify({ targetStatus: 'ARCHIVED', reason: '非法跨级归档' }),
  }, 'admin', 409, 'MATTER_TRANSITION_INVALID')
  await expectApiError(`/matters/${lifecycleMatter.summary.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      title: '越权编辑',
      matterType: 'ADVISORY',
      responsibleUserId: adminId,
      officeId: riyadhOfficeId,
      countryCode: 'SA',
      workingLanguage: 'en-US',
      billingCurrency: 'SAR',
    }),
  }, 'liassistant', 403, 'MATTER_WRITE_DENIED')
})

await check('案件期限创建', async () => {
  const deadline = await api('/deadlines', {
    method: 'POST',
    body: JSON.stringify({
      matterId: createdMatter.summary.id,
      title: `验收期限-${nonce}`,
      dueAt: new Date(Date.now() + 7 * 86400000).toISOString(),
      deadlineType: 'INTERNAL_TASK',
      ownerUserId: adminId,
      priority: 'HIGH',
      reminderDaysBefore: [7, 3, 1],
    }),
  })
  assert(deadline.matterId === createdMatter.summary.id, '期限未关联案件')
  const updated = await api(`/deadlines/${deadline.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      matterId: createdMatter.summary.id,
      title: `验收期限更新-${nonce}`,
      dueAt: new Date(Date.now() + 8 * 86400000).toISOString(),
      deadlineType: 'INTERNAL_TASK',
      ownerUserId: adminId,
      priority: 'URGENT',
      reminderDaysBefore: [5, 2, 1],
    }),
  })
  assert(updated.title.includes('更新'), '期限编辑未生效')
  assert(updated.reminderPolicy.includes('[5, 2, 1]') || updated.reminderPolicy.includes('[5,2,1]'), '提醒策略未保存')
})

await check('合同创建、编辑与案件关联', async () => {
  const created = await api('/contracts', {
    method: 'POST',
    body: JSON.stringify({
      contractNumber: `TEST-CT-${nonce}`,
      title: `验收合同-${nonce}`,
      clientId: createdClient.id,
      responsibleUserId: adminId,
      amount: 125000,
      currency: 'CNY',
      matterIds: [createdMatter.summary.id],
    }),
  })
  assert(created.matterIds.includes(createdMatter.summary.id), '合同未关联案件')
  const updated = await api(`/contracts/${created.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      contractNumber: `TEST-CT-${nonce}`,
      title: `验收合同更新-${nonce}`,
      clientId: createdClient.id,
      responsibleUserId: adminId,
      effectiveDate: new Date().toISOString().slice(0, 10),
      amount: 128000,
      currency: 'CNY',
      matterIds: [createdMatter.summary.id],
    }),
  })
  assert(updated.title.includes('更新') && updated.matterCount === 1, '合同编辑或案件关联未生效')
})

await check('案件工作台聚合与权限范围', async () => {
  const workspace = await api(`/matters/${createdMatter.summary.id}/workspace`)
  assert(workspace.detail.summary.id === createdMatter.summary.id, '案件工作台返回了错误案件')
  assert(workspace.team.some((member) => member.userId === adminId), '案件工作台缺少承办团队')
  assert(workspace.deadlines.some((item) => item.title.includes('验收期限更新')), '案件工作台缺少期限')
  assert(workspace.contracts.some((item) => item.title.includes('验收合同更新')), '案件工作台缺少关联合同')
  assert(Array.isArray(workspace.documents) && Array.isArray(workspace.archives), '案件工作台聚合结构不完整')
})

await check('私有对象存储直传、落库与下载授权', async () => {
  const bytes = Buffer.from(`律师事务所 OA 文档验收 ${nonce}\n`, 'utf8')
  const sha256 = createHash('sha256').update(bytes).digest('hex')
  const ticket = await api('/documents/uploads', {
    method: 'POST',
    body: JSON.stringify({
      matterId: seededMatterId,
      logicalName: `验收文档-${nonce}`,
      documentType: 'CASE_FILE',
      originalFilename: `smoke-${nonce}.txt`,
      contentType: 'text/plain',
      sizeBytes: bytes.length,
      sha256,
    }),
  })
  const upload = await fetch(ticket.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': ticket.requiredContentType },
    body: bytes,
  })
  assert(upload.ok, `对象存储直传失败：${upload.status}`)
  createdDocument = await api(`/documents/uploads/${ticket.uploadId}/complete`, { method: 'POST' })
  const downloadTicket = await api(
    `/documents/${createdDocument.id}/versions/${createdDocument.currentVersionId}/download-url`,
    { method: 'POST' },
  )
  const downloaded = await fetch(downloadTicket.downloadUrl)
  assert(downloaded.ok, `签名下载失败：${downloaded.status}`)
  assert(Buffer.from(await downloaded.arrayBuffer()).equals(bytes), '下载内容与上传内容不一致')
})

await check('文档上下文与文件类型安全边界', async () => {
  await expectApiError('/documents/uploads', {
    method: 'POST',
    body: JSON.stringify({
      logicalName: '无上下文文档',
      documentType: 'CASE_FILE',
      originalFilename: 'context.pdf',
      contentType: 'application/pdf',
      sizeBytes: 10,
      sha256: 'a'.repeat(64),
    }),
  }, 'admin', 400, 'DOCUMENT_CONTEXT_INVALID')
  await expectApiError('/documents/uploads', {
    method: 'POST',
    body: JSON.stringify({
      matterId: seededMatterId,
      logicalName: '可执行文件',
      documentType: 'CASE_FILE',
      originalFilename: 'malware.exe',
      contentType: 'application/x-msdownload',
      sizeBytes: 10,
      sha256: 'a'.repeat(64),
    }),
  }, 'admin', 400, 'FILE_TYPE_NOT_ALLOWED')
})

await check('助理可阅元数据但不能下载原件', async () => {
  const documents = await api(`/documents?matterId=${seededMatterId}`, {}, 'liassistant')
  const item = documents.find((document) => document.id === createdDocument.id)
  assert(item, '案件助理无法查看获授权案件的文档元数据')
  try {
    await api(
      `/documents/${item.id}/versions/${item.currentVersionId}/download-url`,
      { method: 'POST' },
      'liassistant',
    )
    throw new Error('助理在 can_download=false 时仍取得下载地址')
  } catch (error) {
    assert(error.status === 403, `应返回 403，实际为 ${error.status ?? error.message}`)
  }
})

await check('文件摘要不一致时拒绝入库并固化失败状态', async () => {
  const bytes = Buffer.from(`摘要失败验收 ${nonce}\n`, 'utf8')
  const ticket = await api('/documents/uploads', {
    method: 'POST',
    body: JSON.stringify({
      matterId: seededMatterId,
      logicalName: `摘要校验失败-${nonce}`,
      documentType: 'CASE_FILE',
      originalFilename: `bad-hash-${nonce}.txt`,
      contentType: 'text/plain',
      sizeBytes: bytes.length,
      sha256: '0'.repeat(64),
    }),
  })
  const upload = await fetch(ticket.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': ticket.requiredContentType },
    body: bytes,
  })
  assert(upload.ok, '摘要失败用例的对象上传未成功')
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      await api(`/documents/uploads/${ticket.uploadId}/complete`, { method: 'POST' })
      throw new Error('错误摘要的文件被登记入库')
    } catch (error) {
      assert(error.status === 409, `摘要不一致应返回 409，实际为 ${error.status ?? error.message}`)
      if (attempt === 0) {
        assert(error.body?.code === 'UPLOAD_HASH_MISMATCH', '未返回摘要校验错误码')
      } else {
        assert(error.body?.code === 'UPLOAD_SESSION_INVALID', '失败上传会话仍可重复完成')
      }
    }
  }
})

await check('电子卷宗建卷与卷内文件编目', async () => {
  createdArchive = await api('/archives', {
    method: 'POST',
    body: JSON.stringify({
      archiveNumber: `TEST-AJ-${nonce}`,
      title: `自动化验收卷宗-${nonce}`,
      retentionPolicyCode: 'LITIGATION_10Y',
      matterId: seededMatterId,
    }),
  })
  createdArchive = await api(`/archives/${createdArchive.id}/items`, {
    method: 'POST',
    body: JSON.stringify({ documentId: createdDocument.id }),
  })
  assert(createdArchive.itemCount === 1, '卷内文件计数不正确')
  assert(createdArchive.matterId === seededMatterId, '卷宗未绑定案件')
  assert(createdArchive.items.some((item) => item.documentId === createdDocument.id), '卷内文件目录缺失')
})

await check('公告发布、范围可见与已读回执', async () => {
  const draft = await api('/announcements', {
    method: 'POST',
    body: JSON.stringify({
      title: `验收公告-${nonce}`,
      summary: '第二阶段 OA 自动化验收',
      content: '公告正文用于验证发布、通知和已读回执。',
      priority: 'IMPORTANT',
      audienceType: 'ALL',
    }),
  })
  assert(draft.status === 'DRAFT', '公告创建后不是草稿状态')
  const published = await api(`/announcements/${draft.id}/publish`, { method: 'POST' })
  assert(published.status === 'PUBLISHED', '公告发布状态不正确')
  const lawyerAnnouncements = await api('/announcements', {}, 'zhanglawyer')
  assert(lawyerAnnouncements.some((item) => item.id === draft.id), '全所公告对律师不可见')
  const read = await api(`/announcements/${draft.id}/read`, { method: 'POST' }, 'zhanglawyer')
  assert(read.read && read.readCount >= 1, '公告已读回执未保存')
})

await check('公告发布权限与定向范围校验', async () => {
  await expectApiError('/announcements', {
    method: 'POST',
    body: JSON.stringify({
      title: `越权公告-${nonce}`,
      content: '普通律师不应有权创建公告',
      audienceType: 'ALL',
    }),
  }, 'zhanglawyer', 403, 'PERMISSION_DENIED')
  await expectApiError('/announcements', {
    method: 'POST',
    body: JSON.stringify({
      title: `定向公告-${nonce}`,
      content: '必须选择接收人',
      audienceType: 'USERS',
      targetIds: [],
    }),
  }, 'admin', 400, 'ANNOUNCEMENT_TARGET_REQUIRED')
})

await check('协作任务分派、状态流转、评论与可见范围', async () => {
  const task = await api('/work-tasks', {
    method: 'POST',
    body: JSON.stringify({
      title: `整理验收材料-${nonce}`,
      description: '完成归档目录并复核页码',
      ownerUserId: lawyerId,
      dueAt: new Date(Date.now() + 5 * 86400000).toISOString(),
      priority: 'HIGH',
    }),
  })
  assert(task.status === 'TODO' && task.ownerUserId === lawyerId, '任务分派不正确')
  const started = await api(`/work-tasks/${task.id}/status`, {
    method: 'PATCH', body: JSON.stringify({ status: 'IN_PROGRESS' }),
  }, 'zhanglawyer')
  assert(started.status === 'IN_PROGRESS', '任务未进入进行中')
  await api(`/work-tasks/${task.id}/comments`, {
    method: 'POST', body: JSON.stringify({ content: '已完成首轮材料整理' }),
  }, 'zhanglawyer')
  const done = await api(`/work-tasks/${task.id}/status`, {
    method: 'PATCH', body: JSON.stringify({ status: 'DONE' }),
  }, 'zhanglawyer')
  assert(done.status === 'DONE' && done.commentCount === 1, '任务完成或评论计数不正确')
  const assistantTasks = await api('/work-tasks', {}, 'liassistant')
  assert(!assistantTasks.some((item) => item.id === task.id), '无关成员看到了协作任务')
})

await check('请假申请、两级审批与重叠时间拦截', async () => {
  const startAt = new Date(Date.now() + (100 + Number(nonce.slice(-3))) * 86400000)
  const endAt = new Date(startAt.getTime() + 8 * 3600000)
  const leave = await api('/leave-requests', {
    method: 'POST',
    body: JSON.stringify({
      leaveType: 'ANNUAL',
      startAt: startAt.toISOString(),
      endAt: endAt.toISOString(),
      durationHours: 8,
      reason: `自动化请假验收-${nonce}`,
      emergencyContact: '项目组群',
    }),
  }, 'zhanglawyer')
  const submitted = await api(`/leave-requests/${leave.id}/submit`, {
    method: 'POST',
    headers: { 'Idempotency-Key': `smoke-leave-${leave.id}` },
  }, 'zhanglawyer')
  assert(submitted.status === 'SUBMITTED', '请假未进入审批中')

  const overlap = await api('/leave-requests', {
    method: 'POST',
    body: JSON.stringify({
      leaveType: 'PERSONAL',
      startAt: startAt.toISOString(),
      endAt: endAt.toISOString(),
      durationHours: 8,
      reason: `重叠请假验收-${nonce}`,
    }),
  }, 'zhanglawyer')
  try {
    await api(`/leave-requests/${overlap.id}/submit`, {
      method: 'POST',
      headers: { 'Idempotency-Key': `smoke-leave-overlap-${overlap.id}` },
    }, 'zhanglawyer')
    throw new Error('重叠请假被提交')
  } catch (error) {
    assert(error.status === 409, `重叠请假应返回 409，实际为 ${error.status ?? error.message}`)
    assert(error.body?.code === 'LEAVE_TIME_CONFLICT', '重叠请假未返回明确错误码')
  }
  const workflow = (await api('/workflows/inbox?status=PENDING'))
    .find((item) => item.businessType === 'LEAVE_REQUEST' && item.businessId === leave.id)
  assert(workflow, '请假审批读模型缺失')
  const processTasks = await api('/workflows/tasks')
  const first = processTasks.find((item) => item.businessType === 'LEAVE_REQUEST' && item.businessId === leave.id)
  assert(first, '请假审批任务缺失')
  await approveWorkflow(first.processInstanceId)
  const approved = (await api('/leave-requests')).find((item) => item.id === leave.id)
  assert(approved?.status === 'APPROVED', '请假审批未回写 APPROVED')
})

await check('请假类型与起止时间校验', async () => {
  const startAt = new Date(Date.now() + 40 * 86400000)
  await expectApiError('/leave-requests', {
    method: 'POST',
    body: JSON.stringify({
      leaveType: 'SABBATICAL',
      startAt: startAt.toISOString(),
      endAt: new Date(startAt.getTime() + 3600000).toISOString(),
      durationHours: 1,
      reason: '无效类型',
    }),
  }, 'admin', 400, 'LEAVE_TYPE_INVALID')
  await expectApiError('/leave-requests', {
    method: 'POST',
    body: JSON.stringify({
      leaveType: 'ANNUAL',
      startAt: startAt.toISOString(),
      endAt: startAt.toISOString(),
      durationHours: 1,
      reason: '无效时间',
    }),
  }, 'admin', 400, 'LEAVE_TIME_INVALID')
})

await check('费用明细汇总、两级审批与付款登记', async () => {
  const claim = await api('/expense-claims', {
    method: 'POST',
    body: JSON.stringify({
      title: `案件差旅报销-${nonce}`,
      purpose: '客户现场会议',
      matterId: seededMatterId,
      items: [
        { category: '交通费', occurredOn: new Date().toISOString().slice(0, 10), description: '高铁票', amount: 420.5 },
        { category: '住宿费', occurredOn: new Date().toISOString().slice(0, 10), description: '住宿', amount: 680 },
      ],
    }),
  }, 'zhanglawyer')
  assert(Number(claim.totalAmount) === 1100.5 && claim.items.length === 2, '报销明细汇总错误')
  await api(`/expense-claims/${claim.id}/submit`, {
    method: 'POST',
    headers: { 'Idempotency-Key': `smoke-expense-${claim.id}` },
  }, 'zhanglawyer')
  const first = (await api('/workflows/tasks'))
    .find((item) => item.businessType === 'EXPENSE_CLAIM' && item.businessId === claim.id)
  assert(first, '报销审批任务缺失')
  await approveWorkflow(first.processInstanceId)
  const paid = await api(`/expense-claims/${claim.id}/pay`, { method: 'POST' })
  assert(paid.status === 'PAID' && paid.paidAt, '报销付款登记失败')
})

await check('费用空明细与零金额校验', async () => {
  await expectApiError('/expense-claims', {
    method: 'POST',
    body: JSON.stringify({ title: '空报销', purpose: '校验', items: [] }),
  }, 'admin', 400, 'VALIDATION_FAILED')
  await expectApiError('/expense-claims', {
    method: 'POST',
    body: JSON.stringify({
      title: '零金额报销',
      purpose: '校验',
      items: [{
        category: '交通费',
        occurredOn: new Date().toISOString().slice(0, 10),
        description: '零金额',
        amount: 0,
      }],
    }),
  }, 'admin', 400, 'VALIDATION_FAILED')
})

await check('会议室容量、时段冲突、取消与重新预约', async () => {
  const room = await api('/meetings/rooms', {
    method: 'POST',
    body: JSON.stringify({
      name: `验收会议室-${nonce}`,
      location: '自动化测试区',
      capacity: 8,
      facilities: ['无线投屏', '白板'],
    }),
  })
  assert(room.capacity === 8 && room.facilities.includes('无线投屏'), '会议室创建失败')
  const startAt = new Date(Date.now() + 45 * 86400000)
  const endAt = new Date(startAt.getTime() + 3600000)
  const booking = await api('/meetings/bookings', {
    method: 'POST',
    body: JSON.stringify({
      roomId: room.id,
      title: `验收会议-${nonce}`,
      startAt: startAt.toISOString(),
      endAt: endAt.toISOString(),
      attendeeCount: 3,
      attendeeUserIds: [lawyerId],
    }),
  })
  try {
    await api('/meetings/bookings', {
      method: 'POST',
      body: JSON.stringify({
        roomId: room.id,
        title: `冲突会议-${nonce}`,
        startAt: startAt.toISOString(),
        endAt: endAt.toISOString(),
        attendeeCount: 2,
      }),
    })
    throw new Error('同会议室重叠预约成功')
  } catch (error) {
    assert(error.status === 409, `会议冲突应返回 409，实际为 ${error.status ?? error.message}`)
    assert(error.body?.code === 'MEETING_TIME_CONFLICT', '会议冲突未返回明确错误码')
  }
  const cancelled = await api(`/meetings/bookings/${booking.id}/cancel`, { method: 'POST' })
  assert(cancelled.status === 'CANCELLED', '会议取消状态不正确')
  const rebooked = await api('/meetings/bookings', {
    method: 'POST',
    body: JSON.stringify({
      roomId: room.id,
      title: `重新预约-${nonce}`,
      startAt: startAt.toISOString(),
      endAt: endAt.toISOString(),
      attendeeCount: 2,
    }),
  })
  assert(rebooked.status === 'CONFIRMED', '取消后未释放会议时段')
})

await check('会议时间、容量与取消权限边界', async () => {
  const rooms = await api('/meetings/rooms')
  const room = rooms[0]
  const startAt = new Date(Date.now() + 70 * 86400000)
  await expectApiError('/meetings/bookings', {
    method: 'POST',
    body: JSON.stringify({
      roomId: room.id,
      title: '无效时间会议',
      startAt: startAt.toISOString(),
      endAt: startAt.toISOString(),
      attendeeCount: 1,
    }),
  }, 'admin', 400, 'MEETING_TIME_INVALID')
  await expectApiError('/meetings/bookings', {
    method: 'POST',
    body: JSON.stringify({
      roomId: room.id,
      title: '超容量会议',
      startAt: startAt.toISOString(),
      endAt: new Date(startAt.getTime() + 3600000).toISOString(),
      attendeeCount: room.capacity + 1,
    }),
  }, 'admin', 400, 'MEETING_CAPACITY_EXCEEDED')
})

await check('部门统计与组织通讯录', async () => {
  const [departments, directory] = await Promise.all([
    api('/organization/departments'),
    api('/organization/directory'),
  ])
  assert(departments.some((item) => item.memberCount >= 2), '部门成员统计缺失')
  const admin = directory.find((item) => item.username === 'admin')
  assert(admin?.departments && admin?.roles.includes('系统管理员'), '通讯录部门或角色信息缺失')
})

await check('合同两级审批流程', async () => {
  const idempotencyKey = `smoke-contract-${nonce}`
  contractWorkflow = await api('/workflows', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ businessType: 'CONTRACT', businessId: seededContractId }),
  })
  assert(contractWorkflow.status === 'RUNNING', '合同流程未启动')
  const replay = await api('/workflows', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ businessType: 'CONTRACT', businessId: seededContractId }),
  })
  assert(replay.processInstanceId === contractWorkflow.processInstanceId, '审批发起幂等重放失败')
  try {
    await api('/workflows', {
      method: 'POST',
      body: JSON.stringify({ businessType: 'CONTRACT', businessId: seededContractId }),
    })
    throw new Error('进行中的审批被重复启动')
  } catch (error) {
    assert(error.status === 409, `重复启动应返回 409，实际为 ${error.status ?? error.message}`)
  }
  const firstTask = (await api('/workflows/tasks'))
    .find((item) => item.processInstanceId === contractWorkflow.processInstanceId)
  assert(firstTask, '合同审批首级任务不存在')
  await expectApiError(`/workflows/tasks/${firstTask.id}/complete`, {
    method: 'POST',
    body: JSON.stringify({ decision: 'REJECT', comment: ' ' }),
  }, 'admin', 400, 'REJECT_REASON_REQUIRED')
  const transferTargets = await api(`/workflows/tasks/${firstTask.id}/transfer-targets`)
  const adminTarget = transferTargets.find((item) => item.userId === adminId)
  assert(adminTarget, '审批转交候选人未按业务权限返回当前管理员')
  const transferred = await api(`/workflows/tasks/${firstTask.id}/transfer`, {
    method: 'POST',
    headers: { 'Idempotency-Key': `smoke-transfer-${firstTask.id}` },
    body: JSON.stringify({ targetUserId: adminId, comment: '转交资格验收' }),
  })
  assert(transferred.decision === 'TRANSFER', '审批转交未成功')
  await api(`/workflows/tasks/${firstTask.id}/remind`, { method: 'POST' })
  await expectApiError(`/workflows/tasks/${firstTask.id}/remind`, {
    method: 'POST',
  }, 'admin', 429, 'WORKFLOW_REMIND_RATE_LIMITED')
  for (let step = 0; step < 2; step += 1) {
    const tasks = await api('/workflows/tasks')
    const task = tasks.find((item) => item.processInstanceId === contractWorkflow.processInstanceId)
    assert(task, `合同审批第 ${step + 1} 级任务不存在`)
    const taskIdempotencyKey = `smoke-task-${task.id}`
    const action = await api(`/workflows/tasks/${task.id}/complete`, {
      method: 'POST',
      headers: { 'Idempotency-Key': taskIdempotencyKey },
      body: JSON.stringify({
        decision: 'APPROVE',
        comment: `自动化验收第 ${step + 1} 级通过`,
        variables: { approved: true },
      }),
    })
    const actionReplay = await api(`/workflows/tasks/${task.id}/complete`, {
      method: 'POST',
      headers: { 'Idempotency-Key': taskIdempotencyKey },
      body: JSON.stringify({
        decision: 'APPROVE',
        comment: `自动化验收第 ${step + 1} 级通过`,
      }),
    })
    assert(actionReplay.taskId === action.taskId, '审批动作幂等重放失败')
  }
  const remaining = await api('/workflows/tasks')
  assert(!remaining.some((item) => item.processInstanceId === contractWorkflow.processInstanceId), '合同流程未结束')
  const contracts = await api('/contracts')
  assert(
    contracts.find((item) => item.id === seededContractId)?.status === 'APPROVED',
    '合同审批通过后未回写 APPROVED',
  )
})

await check('审批类型和任务权限边界', async () => {
  await expectApiError('/workflows', {
    method: 'POST',
    body: JSON.stringify({
      businessType: 'UNKNOWN_BUSINESS',
      businessId: seededMatterId,
      variables: {},
    }),
  }, 'admin', 400, 'WORKFLOW_TYPE_INVALID')
})

await check('案件审批驳回与业务状态回写', async () => {
  const workflow = await api('/workflows', {
    method: 'POST',
    body: JSON.stringify({ businessType: 'MATTER', businessId: createdMatter.summary.id }),
  })
  const task = (await api('/workflows/tasks'))
    .find((item) => item.processInstanceId === workflow.processInstanceId)
  assert(task, '案件驳回测试未生成审批任务')
  const action = await api(`/workflows/tasks/${task.id}/complete`, {
    method: 'POST',
    body: JSON.stringify({ decision: 'REJECT', comment: '冒烟测试驳回' }),
  })
  assert(action.workflowStatus === 'REJECTED', '驳回未终止流程')
  const matter = await api(`/matters/${createdMatter.summary.id}`)
  assert(matter.summary.status === 'REJECTED', '案件驳回后未回写 REJECTED')
})

await check('用印申请与两级审批流程', async () => {
  sealRequest = await api('/seals/requests', {
    method: 'POST',
    body: JSON.stringify({
      sealId: seededSealId,
      matterId: seededMatterId,
      purpose: `验收用印-${nonce}`,
      copies: 2,
    }),
  })
  const workflow = await api('/workflows', {
    method: 'POST',
    body: JSON.stringify({ businessType: 'SEAL_REQUEST', businessId: sealRequest.id }),
  })
  for (let step = 0; step < 2; step += 1) {
    const tasks = await api('/workflows/tasks')
    const task = tasks.find((item) => item.processInstanceId === workflow.processInstanceId)
    assert(task, `用印审批第 ${step + 1} 级任务不存在`)
    if (step === 0) {
      const transferred = await api(`/workflows/tasks/${task.id}/transfer`, {
        method: 'POST',
        body: JSON.stringify({ targetUserId: adminId, comment: '转交能力验收' }),
      })
      assert(transferred.decision === 'TRANSFER', '审批转交失败')
    }
    await api(`/workflows/tasks/${task.id}/complete`, {
      method: 'POST',
      body: JSON.stringify({
        decision: 'APPROVE',
        comment: `自动化验收第 ${step + 1} 级通过`,
      }),
    })
  }
  const requests = await api('/seals/requests')
  assert(
    requests.find((item) => item.id === sealRequest.id)?.status === 'APPROVED',
    '用印审批通过后未回写 APPROVED',
  )
})

await check('统一待办、通知中心与已读状态', async () => {
  const completed = await api('/workflows/inbox?status=APPROVED')
  assert(completed.length >= 4, '已办审批读模型缺失')
  let page
  for (let attempt = 0; attempt < 20; attempt += 1) {
    page = await api('/notifications?status=UNREAD')
    if (page.total > 0) break
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
  assert(page.total > 0, 'Outbox 未投递站内通知')
  const unread = await api('/notifications/unread-count')
  assert(unread.count > 0, '未读通知计数不正确')
  await api(`/notifications/${page.items[0].id}/read`, { method: 'PATCH' })
  const readPage = await api('/notifications?status=READ')
  assert(readPage.items.some((item) => item.id === page.items[0].id), '通知已读状态未保存')
})

await check('审计检索与权限隔离', async () => {
  const audits = await api('/audit-logs?resourceType=WORKFLOW_LINK')
  assert(audits.total >= 4, '审批审计日志缺失')
  try {
    await api('/audit-logs', {}, 'zhanglawyer')
    throw new Error('普通律师读取了全局审计日志')
  } catch (error) {
    assert(error.status === 403, `审计权限隔离应返回 403，实际为 ${error.status ?? error.message}`)
  }
})

await check('办公室通讯录、办公室列表与会议室数据隔离', async () => {
  const [me, offices, users, directory, rooms] = await Promise.all([
    api('/me', {}, 'zhanglawyer'),
    api('/offices', {}, 'zhanglawyer'),
    api('/organization/users', {}, 'zhanglawyer'),
    api('/organization/directory', {}, 'zhanglawyer'),
    api('/meetings/rooms', {}, 'zhanglawyer'),
  ])
  assert(me.globalOfficeAccess === false, '普通律师不应具有全所办公室权限')
  assert(
    me.accessibleOffices.length === 1 && me.accessibleOffices[0].id === shanghaiOfficeId,
    '当前用户可访问办公室范围不正确',
  )
  assert(offices.length === 1 && offices[0].id === shanghaiOfficeId, '办公室列表发生越权')
  assert(users.length === 1 && users[0].username === 'zhanglawyer', '组织用户列表发生越权')
  assert(
    directory.length === 1 && directory[0].username === 'zhanglawyer',
    '通讯录发生跨办公室越权',
  )
  assert(rooms.length === 0, '上海律师看到了迪拜会议室')
})

await check('案件成员保留跨办公室协作权限', async () => {
  const matters = await api('/matters', {}, 'zhanglawyer')
  assert(
    matters.some((item) => item.id === seededMatterId && item.officeId !== shanghaiOfficeId),
    '跨办公室案件成员无法访问已授权案件',
  )
})

await check('普通用户不能在未授权办公室立案', async () => {
  await expectApiError('/matters', {
    method: 'POST',
    body: JSON.stringify({
      matterNumber: `DENIED-OFFICE-${nonce}`,
      title: `越权办公室案件-${nonce}`,
      matterType: 'ADVISORY',
      responsibleUserId: lawyerId,
      officeId: riyadhOfficeId,
      clientIds: [],
      parties: [],
    }),
  }, 'zhanglawyer', 403, 'OFFICE_ACCESS_DENIED')
})

await check('办公室公告只投递给授权办公室', async () => {
  const draft = await api('/announcements', {
    method: 'POST',
    body: JSON.stringify({
      title: `上海办公室公告-${nonce}`,
      summary: '办公室隔离自动验收',
      content: '仅上海办公室成员可见',
      category: 'NOTICE',
      priority: 'NORMAL',
      audienceType: 'ALL',
      targetIds: [],
      officeId: shanghaiOfficeId,
    }),
  })
  await api(`/announcements/${draft.id}/publish`, { method: 'POST' })
  const [shanghaiAnnouncements, beijingAnnouncements] = await Promise.all([
    api('/announcements', {}, 'zhanglawyer'),
    api('/announcements', {}, 'liassistant'),
  ])
  assert(
    shanghaiAnnouncements.some((item) => item.id === draft.id),
    '上海办公室成员未看到办公室公告',
  )
  assert(
    !beijingAnnouncements.some((item) => item.id === draft.id),
    '北京办公室成员越权看到上海公告',
  )
})

await check('多来源组织快照同步与外部身份映射', async () => {
  const externalDepartmentId = `manual-dept-${nonce}`
  const externalUserId = `manual-user-${nonce}`
  const sync = await api('/organization/sync', {
    method: 'POST',
    body: JSON.stringify({
      provider: 'MANUAL',
      mode: 'SNAPSHOT',
      deactivateMissingUsers: false,
      departments: [{
        externalDepartmentId,
        name: `验收部门-${nonce}`,
        sortOrder: 99,
      }],
      users: [{
        externalUserId,
        username: `sync${nonce}`,
        displayName: `同步用户-${nonce}`,
        email: `sync${nonce}@example.local`,
        status: 'ACTIVE',
        departmentExternalIds: [externalDepartmentId],
        rawProfile: { source: 'smoke-test' },
      }],
    }),
  })
  assert(sync.status === 'SUCCEEDED' && sync.usersChanged === 1, '组织快照同步失败')
  const users = await api('/organization/users')
  const syncedUser = users.find((item) => item.username === `sync${nonce}`)
  assert(syncedUser, '同步用户未进入本地通讯录')
  const runs = await api('/organization/sync/runs')
  assert(runs.some((item) => item.runId === sync.runId), '组织同步运行记录缺失')

  const validUntil = new Date(Date.now() + 60 * 60 * 1000).toISOString()
  const membership = await api(`/offices/${shanghaiOfficeId}/members/${syncedUser.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      accessLevel: 'MEMBER',
      primary: false,
      validUntil,
    }),
  })
  assert(
    membership.userId === syncedUser.id && membership.validUntil,
    '临时办公室授权未保存',
  )
  const syncedOffices = await api('/offices', {}, `sync${nonce}`)
  assert(
    syncedOffices.length === 1 && syncedOffices[0].id === shanghaiOfficeId,
    '临时授权用户未获得对应办公室范围',
  )

  await api(`/offices/${shanghaiOfficeId}/members/${lawyerId}`, {
    method: 'PUT',
    body: JSON.stringify({ accessLevel: 'MANAGER', primary: true, validUntil: null }),
  })
  await expectApiError(`/offices/${shanghaiOfficeId}/members/${syncedUser.id}`, {
    method: 'PUT',
    body: JSON.stringify({ accessLevel: 'MANAGER', primary: false, validUntil }),
  }, 'zhanglawyer', 403, 'OFFICE_GRANT_DENIED')
  const localManagerGrant = await api(
    `/offices/${shanghaiOfficeId}/members/${syncedUser.id}`,
    {
      method: 'PUT',
      body: JSON.stringify({ accessLevel: 'MEMBER', primary: false, validUntil }),
    },
    'zhanglawyer',
  )
  assert(localManagerGrant.accessLevel === 'MEMBER', '办公室管理员未能维护普通成员')
  await api(`/offices/${shanghaiOfficeId}/members/${lawyerId}`, {
    method: 'PUT',
    body: JSON.stringify({ accessLevel: 'MEMBER', primary: true, validUntil: null }),
  })
  await api(`/offices/${shanghaiOfficeId}/members/${syncedUser.id}`, { method: 'DELETE' })
  const revokedOffices = await api('/offices', {}, `sync${nonce}`)
  assert(revokedOffices.length === 0, '撤销授权后用户仍可访问办公室')
})

await check('文档下载突发告警、硬限流与安全事件权限', async () => {
  let limited = false
  for (let attempt = 0; attempt < 31; attempt += 1) {
    try {
      await api(
        `/documents/${createdDocument.id}/versions/${createdDocument.currentVersionId}/download-url`,
        { method: 'POST' },
        'zhanglawyer',
      )
    } catch (error) {
      assert(error.status === 429, `高频下载应返回 429，实际为 ${error.status ?? error.message}`)
      assert(
        error.body?.code === 'DOCUMENT_DOWNLOAD_RATE_LIMITED',
        '高频下载未返回稳定错误码',
      )
      limited = true
      break
    }
  }
  assert(limited, '31 次下载地址申请内未触发硬限流')
  const events = await api('/document-security/events?limit=100')
  assert(
    events.some((event) =>
      event.userId === lawyerId
      && event.eventType === 'DOWNLOAD_RATE_LIMITED'
      && event.documentId === createdDocument.id),
    '下载限流安全事件未留痕',
  )
  assert(
    events.some((event) =>
      event.userId === lawyerId && event.eventType === 'DOWNLOAD_BURST_WARNING'),
    '下载突发预警事件未留痕',
  )
  await expectApiError(
    '/document-security/events',
    {},
    'zhanglawyer',
    403,
    'PERMISSION_DENIED',
  )
})

console.log(`\n全部通过：${checks.length} 项功能验证。`)
