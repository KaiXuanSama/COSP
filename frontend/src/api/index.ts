import axios from 'axios'

const TOKEN_KEY = 'cosp_token'

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
    if (error.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      // 避免在登录页重复跳转
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login?unauthorized=true'
      }
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
 * 获取日志列表（分页）
 */
export function fetchLogs(pageNum: number, pageSize: number) {
  return http.get('/logs', { params: { pageNum, pageSize } })
}

/**
 * 获取单条日志详情
 */
export function fetchLogDetail(id: number) {
  return http.get(`/logs/${id}`)
}

export default http