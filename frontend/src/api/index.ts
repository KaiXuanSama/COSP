import axios from 'axios'

const TOKEN_KEY = 'cosp_token'

function shouldSkipAuthRedirect(error: any) {
 return Boolean((error?.config as any)?.skipAuthRedirect)
}

/**
 * 清除 token 并整页跳转到登录页，带上当前地址供登录后回跳。
 *
 * 供 axios 拦截器与 SSE 客户端共用：两者都在 Vue 路由之外，拿不到 router 实例，
 * 只能整页跳转。已在登录页时不再跳，避免把 redirect 覆写成 /login 自身。
 */
export function redirectToLogin() {
  localStorage.removeItem(TOKEN_KEY)
  if (window.location.pathname.startsWith('/login')) return
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.href = `/login?unauthorized=true&redirect=${redirect}`
}

const http = axios.create({
  baseURL: '/config/api',
  timeout: 10000,
})

// 请求拦截器：自动注入 Bearer Token
http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：401 时清除 token 并跳转登录页
http.interceptors.response.use(
  (res) => res,
  (error) => {
 if (error.response?.status ===401 && !shouldSkipAuthRedirect(error)) {
      redirectToLogin()
    }
    return Promise.reject(error)
  },
)

/** 认证相关工具函数 */
export const auth = {
  getToken: () => localStorage.getItem(TOKEN_KEY),
  setToken: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clearToken: () => localStorage.removeItem(TOKEN_KEY),
  isAuthenticated: () => !!localStorage.getItem(TOKEN_KEY),
}

/**
 * 获取日志列表（游标分页）
 */
export function fetchLogs(cursor: number | null, pageSize: number) {
  return http.get('/logs', { params: { cursor, pageSize } })
}

/**
 * 获取单条日志详情
 */
export function fetchLogDetail(id: number) {
  return http.get(`/logs/${id}`)
}

export default http