import { createRouter, createWebHistory } from 'vue-router'
import Login from '@/views/Login.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'
import Overview from '@/views/Overview.vue'
import Settings from '@/views/Settings.vue'
import Account from '@/views/Account.vue'

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
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：检查认证状态
// 使用 fetch + redirect: manual 避免 Axios 自动跟随 Spring Security 的 302 重定向
router.beforeEach(async (to, from, next) => {
  if (to.meta.requiresAuth) {
    try {
      const res = await fetch('/config/api/me', { redirect: 'manual' })
      if (res.status === 200) {
        next()
      } else {
        next('/login?unauthorized=true')
      }
    } catch {
      next('/login?unauthorized=true')
    }
  } else {
    next()
  }
})

export default router