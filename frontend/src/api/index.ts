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

export default http