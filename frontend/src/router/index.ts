import { createRouter, createWebHistory } from 'vue-router'
import Login from '@/views/Login.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'
import Overview from '@/views/Overview.vue'
import Settings from '@/views/Settings.vue'
import Account from '@/views/Account.vue'
import CallLog from '@/views/CallLog.vue'
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
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：检查本地 JWT token，无 token 时跳转登录页
router.beforeEach((to, from, next) => {
  if (to.meta.requiresAuth) {
    if (auth.isAuthenticated()) {
      next()
    } else {
      next('/login?unauthorized=true')
    }
  } else {
    next()
  }
})

export default router