import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../api'

export const useCartStore = defineStore('cart', () => {
  const cartItems = ref([])
  const cartCount = ref(0)

  async function fetchCartCount(userId) {
    // 未登录直接置零，避免无 token 调用产生 401
    if (!userId || !localStorage.getItem('token')) {
      cartCount.value = 0
      return
    }
    try {
      const res = await api.getCartCount(userId)
      cartCount.value = res.data
    } catch (e) {
      // 401 已由 axios 拦截器统一处理（清理凭据并跳转登录），此处仅记录其他错误
      if (e?.response?.status !== 401) {
        console.error('获取购物车数量失败', e)
      }
    }
  }

  function setCartItems(items) {
    cartItems.value = items
    cartCount.value = items.length
  }

  function addCartItem(item) {
    const existing = cartItems.value.find(i => i.productId === item.productId)
    if (existing) {
      existing.quantity += item.quantity
    } else {
      cartItems.value.push(item)
    }
    cartCount.value = cartItems.value.reduce((sum, i) => sum + i.quantity, 0)
  }

  function removeCartItem(productId) {
    cartItems.value = cartItems.value.filter(i => i.productId !== productId)
    cartCount.value = cartItems.value.reduce((sum, i) => sum + i.quantity, 0)
  }

  function clearCart() {
    cartItems.value = []
    cartCount.value = 0
  }

  return { cartItems, cartCount, fetchCartCount, setCartItems, addCartItem, removeCartItem, clearCart }
})