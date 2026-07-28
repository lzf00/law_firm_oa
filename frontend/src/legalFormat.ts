import type { Locale } from '@/i18n'

type BilingualLabel = Record<Locale, string>

const labels: Record<string, BilingualLabel> = {
  ACTIVE: { 'zh-CN': '在办', 'en-US': 'Active' },
  APPROVED: { 'zh-CN': '已通过', 'en-US': 'Approved' },
  AVAILABLE: { 'zh-CN': '可用', 'en-US': 'Available' },
  ARCHIVED: { 'zh-CN': '已归档', 'en-US': 'Archived' },
  CANCELLED: { 'zh-CN': '已取消', 'en-US': 'Cancelled' },
  CLOSED: { 'zh-CN': '已结案', 'en-US': 'Closed' },
  COMPLETED: { 'zh-CN': '已完成', 'en-US': 'Completed' },
  CONFLICT_REVIEW: { 'zh-CN': '冲突审查中', 'en-US': 'Conflict review' },
  DRAFT: { 'zh-CN': '草稿', 'en-US': 'Draft' },
  DONE: { 'zh-CN': '已完成', 'en-US': 'Done' },
  EXPIRED: { 'zh-CN': '已到期', 'en-US': 'Expired' },
  IN_PROGRESS: { 'zh-CN': '进行中', 'en-US': 'In progress' },
  INTAKE: { 'zh-CN': '待立案', 'en-US': 'Intake' },
  OPEN: { 'zh-CN': '开放', 'en-US': 'Open' },
  OVERDUE: { 'zh-CN': '已逾期', 'en-US': 'Overdue' },
  PAID: { 'zh-CN': '已付款', 'en-US': 'Paid' },
  PENDING: { 'zh-CN': '待处理', 'en-US': 'Pending' },
  PUBLISHED: { 'zh-CN': '已发布', 'en-US': 'Published' },
  CONFIRMED: { 'zh-CN': '已确认', 'en-US': 'Confirmed' },
  REJECTED: { 'zh-CN': '已驳回', 'en-US': 'Rejected' },
  REVIEWING: { 'zh-CN': '审批中', 'en-US': 'In review' },
  SIGNED: { 'zh-CN': '已签署', 'en-US': 'Signed' },
  SUBMITTED: { 'zh-CN': '已提交', 'en-US': 'Submitted' },
  SUSPENDED: { 'zh-CN': '已暂停', 'en-US': 'Suspended' },
  TERMINATED: { 'zh-CN': '已终止', 'en-US': 'Terminated' },
  TODO: { 'zh-CN': '待开始', 'en-US': 'To do' },
  UNREAD: { 'zh-CN': '未读', 'en-US': 'Unread' },
  READ: { 'zh-CN': '已读', 'en-US': 'Read' },
  LOW: { 'zh-CN': '低', 'en-US': 'Low' },
  MEDIUM: { 'zh-CN': '中', 'en-US': 'Medium' },
  NORMAL: { 'zh-CN': '普通', 'en-US': 'Normal' },
  HIGH: { 'zh-CN': '高', 'en-US': 'High' },
  IMPORTANT: { 'zh-CN': '重要', 'en-US': 'Important' },
  URGENT: { 'zh-CN': '紧急', 'en-US': 'Urgent' },
  CLEAR: { 'zh-CN': '无冲突', 'en-US': 'Clear' },
  WAIVER_REQUIRED: { 'zh-CN': '需豁免', 'en-US': 'Waiver required' },
  REJECT: { 'zh-CN': '驳回', 'en-US': 'Reject' },
  APPROVE: { 'zh-CN': '通过', 'en-US': 'Approve' },
  TRANSFER: { 'zh-CN': '转交', 'en-US': 'Transfer' },
  PERSON: { 'zh-CN': '自然人', 'en-US': 'Individual' },
  ORGANIZATION: { 'zh-CN': '机构主体', 'en-US': 'Organization' },
  GOVERNMENT: { 'zh-CN': '政府机构', 'en-US': 'Government' },
  OTHER: { 'zh-CN': '其他', 'en-US': 'Other' },
  ANNUAL: { 'zh-CN': '年假', 'en-US': 'Annual leave' },
  PERSONAL: { 'zh-CN': '事假', 'en-US': 'Personal leave' },
  SICK: { 'zh-CN': '病假', 'en-US': 'Sick leave' },
  MARRIAGE: { 'zh-CN': '婚假', 'en-US': 'Marriage leave' },
  MATERNITY: { 'zh-CN': '产假', 'en-US': 'Maternity leave' },
  RESPONSIBLE: { 'zh-CN': '承办负责人', 'en-US': 'Responsible counsel' },
  LEAD: { 'zh-CN': '主办律师', 'en-US': 'Lead counsel' },
  COUNSEL: { 'zh-CN': '办案律师', 'en-US': 'Counsel' },
  ASSISTANT: { 'zh-CN': '律师助理', 'en-US': 'Assistant' },
  ADMIN: { 'zh-CN': '系统管理员', 'en-US': 'System administrator' },
  ADMINISTRATION: { 'zh-CN': '行政', 'en-US': 'Administration' },
  LAWYER: { 'zh-CN': '律师', 'en-US': 'Lawyer' },
  MANAGING_PARTNER: { 'zh-CN': '管理合伙人', 'en-US': 'Managing partner' },
  OFFICE_ADMIN: { 'zh-CN': '办公室管理员', 'en-US': 'Office administrator' },
  OBSERVER: { 'zh-CN': '观察成员', 'en-US': 'Observer' },
  COURT: { 'zh-CN': '法院期限', 'en-US': 'Court deadline' },
  ARBITRATION: { 'zh-CN': '仲裁', 'en-US': 'Arbitration' },
  FILING: { 'zh-CN': '申报期限', 'en-US': 'Filing deadline' },
  INTERNAL: { 'zh-CN': '内部', 'en-US': 'Internal' },
  INTERNAL_TASK: { 'zh-CN': '内部任务', 'en-US': 'Internal task' },
  CASE_FILE: { 'zh-CN': '案件文件', 'en-US': 'Matter file' },
  FINAL: { 'zh-CN': '定稿', 'en-US': 'Final' },
  CONFIDENTIAL: { 'zh-CN': '机密', 'en-US': 'Confidential' },
  RESTRICTED: { 'zh-CN': '严格保密', 'en-US': 'Restricted' },
  PUBLIC: { 'zh-CN': '公开', 'en-US': 'Public' },
  CLIENT: { 'zh-CN': '客户方', 'en-US': 'Client' },
  OPPOSING: { 'zh-CN': '相对方', 'en-US': 'Opposing party' },
  RELATED: { 'zh-CN': '关联方', 'en-US': 'Related party' },
  CROSS_BORDER: { 'zh-CN': '跨境业务', 'en-US': 'Cross-border' },
  LITIGATION: { 'zh-CN': '诉讼', 'en-US': 'Litigation' },
  CORPORATE: { 'zh-CN': '公司业务', 'en-US': 'Corporate' },
  ADVISORY: { 'zh-CN': '法律顾问', 'en-US': 'Advisory' },
  'zh-CN': { 'zh-CN': '中文', 'en-US': 'Chinese' },
  'en-US': { 'zh-CN': '英文', 'en-US': 'English' },
  ar: { 'zh-CN': '阿拉伯文', 'en-US': 'Arabic' },
  LITIGATION_10Y: { 'zh-CN': '诉讼卷宗 · 10年', 'en-US': 'Litigation · 10 years' },
  PERMANENT: { 'zh-CN': '永久保管', 'en-US': 'Permanent' },
  GENERAL_5Y: { 'zh-CN': '一般业务 · 5年', 'en-US': 'General · 5 years' },
}

export function formatLegalCode(value: string | null | undefined, locale: Locale) {
  if (!value) return '—'
  return labels[value]?.[locale] ?? value
}

export function formatLegalCodeList(value: string | null | undefined, locale: Locale) {
  if (!value) return '—'
  return value
    .split(/\s*[,，]\s*/)
    .filter(Boolean)
    .map((code) => formatLegalCode(code, locale))
    .join(locale === 'zh-CN' ? '、' : ', ')
}

export function hasCompleteLegalLabels() {
  return Object.entries(labels).every(([, label]) => Boolean(label['zh-CN'] && label['en-US']))
}
