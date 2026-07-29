export interface CurrentUser {
  userId: string
  organizationId: string
  username: string
  displayName: string
  authorities: string[]
  roles: string[]
  permissions: string[]
  preferredLocale: 'zh-CN' | 'en-US'
  primaryOfficeId?: string
  officeNameZh?: string
  officeNameEn?: string
  timezone?: string
  defaultCurrency?: string
  globalOfficeAccess: boolean
  accessibleOffices: AccessibleOffice[]
}

export interface AccessibleOffice {
  id: string
  code: string
  nameZh: string
  nameEn: string
  timezone: string
  defaultCurrency: string
  manageable: boolean
}

export interface TenantConfiguration {
  organizationId: string
  brandNameZh: string
  brandNameEn: string
  shortNameZh: string
  shortNameEn: string
  defaultLocale: 'zh-CN' | 'en-US'
  supportedLocales: Array<'zh-CN' | 'en-US'>
  primaryTimezone: string
  baseCurrency: string
  websiteUrl?: string
}

export interface Office {
  id: string
  code: string
  nameZh: string
  nameEn: string
  countryCode: string
  cityZh: string
  cityEn: string
  timezone: string
  defaultCurrency: string
  addressZh?: string
  addressEn?: string
  memberCount: number
}

export interface OfficeMember {
  userId: string
  username: string
  displayName: string
  email?: string
  accessLevel: 'MEMBER' | 'MANAGER'
  primary: boolean
  validUntil?: string
  createdAt: string
  updatedAt: string
  assignedByName?: string
}

export interface OrganizationUser {
  id: string
  username: string
  displayName: string
  status: string
}

export interface Party {
  id: string
  partyType: 'PERSON' | 'ORGANIZATION' | 'GOVERNMENT' | 'OTHER'
  displayName: string
  unifiedSocialCreditCode?: string
  riskLevel: string
  aliases: string[]
  notes?: string
}

export interface Client {
  id: string
  partyId: string
  clientNumber: string
  displayName: string
  partyType: string
  ownerUserId?: string
  ownerName?: string
  status: string
  source?: string
}

export interface Matter {
  id: string
  matterNumber: string
  title: string
  matterType: string
  status: string
  confidentialityLevel: string
  responsibleUserId: string
  responsibleName: string
  openedAt?: string
  memberCount: number
  officeId?: string
  officeNameZh?: string
  officeNameEn?: string
  countryCode?: string
  jurisdiction?: string
  workingLanguage: 'zh-CN' | 'en-US' | 'ar'
  billingCurrency: string
}
