const configuredApiBase = import.meta.env.VITE_API_BASE_URL || '/api'

export const apiBaseUrl = configuredApiBase.replace(/\/+$/, '')

export function apiUrl(path: string) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return `${apiBaseUrl}${normalizedPath}`
}

export function appUrl(path: string) {
  const normalizedPath = path.replace(/^\/+/, '')
  return `${import.meta.env.BASE_URL}${normalizedPath}`
}
