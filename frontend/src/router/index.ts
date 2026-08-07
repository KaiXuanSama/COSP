import { createRouter, createWebHistory } from 'vue-router'
import Login from '@/views/Login.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'
import Overview from '@/views/Overview.vue'
import Settings from '@/views/Settings.vue'
import Preferences from '@/views/Preferences.vue'
import Account from '@/views/Account.vue'
import CallLog from '@/views/CallLog.vue'
import UsageLog from '@/views/UsageLog.vue'
import NotFound from '@/views/NotFound.vue'
import { auth } from '@/api'

const routes = [
  {
    path: '/',
    redirect: '/login',
  },
  {
    path: '/login',
    name: 'login',
    component: Login,
  },
  {
    path: '/overview',
    component: AdminLayout,
    children: [
      {
        path: '',
        name: 'overview',
        component: Overview,
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/settings',
    component: AdminLayout,
    children: [
      {
        path: '',
        name: 'settings',
        component: Settings,
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/preferences',
    component: AdminLayout,
    children: [
      {
        path: '',
        name: 'preferences',
        component: Preferences,
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/account',
    component: AdminLayout,
    children: [
      {
        path: '',
        name: 'account',
        component: Account,
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/call-log',
    component: AdminLayout,
    children: [
      {
        path: '',
        name: 'call-log',
        component: CallLog,
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/usage-log',
    component: AdminLayout,
    children: [
      {
        path: '',
        name: 'usage-log',
        component: UsageLog,
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    // 兜底 404。后端把所有非 API 的浏览器请求都回退成 index.html，
    // 拼错的地址会走到前端路由，没有这条 catch-all 就会渲染成空白页。
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: NotFound,
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：检查本地 JWT token，无 token 时跳登录页并记下原地址供登录后回跳。
// 注意这里只看 token 是否存在、不解析 exp —— 过期 token 仍会放行，
// 由页面内首个请求的 401 触发 api 拦截器兜底跳转。
router.beforeEach((to, from, next) => {
  if (!to.meta.requiresAuth || auth.isAuthenticated()) {
    next()
    return
  }
  next({ name: 'login', query: { unauthorized: 'true', redirect: to.fullPath } })
})

export default router