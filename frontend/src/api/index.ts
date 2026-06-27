import axios from 'axios'

const http = axios.create({
  baseURL: '/config/api',
  timeout: 10000,
})

http.interceptors.response.use(
  (res) => res,
  (error) => {
    console.error('API 请求失败:', error.message)
    return Promise.reject(error)
  },
)

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