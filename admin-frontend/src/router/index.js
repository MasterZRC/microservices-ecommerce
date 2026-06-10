import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../components/AdminLayout.vue'
import Login from '../views/Login.vue'
import Dashboard from '../views/Dashboard.vue'
import ProductList from '../views/product/ProductList.vue'
import ProductForm from '../views/product/ProductForm.vue'
import OrderList from '../views/order/OrderList.vue'
import OrderDetail from '../views/order/OrderDetail.vue'
import SeckillList from '../views/seckill/SeckillList.vue'
import SeckillForm from '../views/seckill/SeckillForm.vue'
import SeckillDemo from '../views/seckill/SeckillDemo.vue'
import AIInsights from '../views/AIInsights.vue'

const routes = [
  {
    path: '/admin/login',
    name: 'Login',
    component: Login,
    meta: { title: '登录', requiresAuth: false }
  },
  {
    path: '/admin',
    component: AdminLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/admin/dashboard' },
      { path: 'dashboard', name: 'Dashboard', component: Dashboard, meta: { title: '首页概览' } },
      { path: 'products', name: 'ProductList', component: ProductList, meta: { title: '商品管理' } },
      { path: 'products/create', name: 'ProductCreate', component: ProductForm, meta: { title: '创建商品' } },
      { path: 'products/edit/:id', name: 'ProductEdit', component: ProductForm, meta: { title: '编辑商品' } },
      { path: 'orders', name: 'OrderList', component: OrderList, meta: { title: '订单管理' } },
      { path: 'orders/:id', name: 'OrderDetail', component: OrderDetail, meta: { title: '订单详情' } },
      { path: 'seckill', name: 'SeckillList', component: SeckillList, meta: { title: '秒杀管理' } },
      { path: 'seckill/demo', name: 'SeckillDemo', component: SeckillDemo, meta: { title: '秒杀压测' } },
      { path: 'seckill/create', name: 'SeckillCreate', component: SeckillForm, meta: { title: '创建秒杀' } },
      { path: 'seckill/edit/:id', name: 'SeckillEdit', component: SeckillForm, meta: { title: '编辑秒杀' } },
      { path: 'ai-insights', name: 'AIInsights', component: AIInsights, meta: { title: 'AI 经营助手' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/admin/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('admin_token')
  if (to.meta.requiresAuth !== false && !token) {
    next('/admin/login')
  } else if (to.path === '/admin/login' && token) {
    next('/admin/dashboard')
  } else {
    next()
  }
})

export default router
