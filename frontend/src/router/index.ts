import { createRouter, createWebHistory } from 'vue-router'
import Login from '@/views/Login.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'
import Overview from '@/views/Overview.vue'
import Settings from '@/views/Settings.vue'
import Account from '@/views/Account.vue'

const routes = [
  {
    path: '/',
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
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory('/admin/'),
  routes,
})

export default router