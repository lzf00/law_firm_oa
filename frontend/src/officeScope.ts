import type { CurrentUser } from './api/types'

export function canAccessOffice(user: CurrentUser, officeId: string): boolean {
  return user.globalOfficeAccess
    || user.accessibleOffices.some((office) => office.id === officeId)
}

export function canManageOffice(user: CurrentUser, officeId: string): boolean {
  return user.globalOfficeAccess
    || user.accessibleOffices.some((office) => office.id === officeId && office.manageable)
}

export function manageableOffices(user: CurrentUser) {
  return user.accessibleOffices.filter((office) => user.globalOfficeAccess || office.manageable)
}
