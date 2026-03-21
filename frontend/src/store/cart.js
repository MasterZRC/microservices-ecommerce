import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../api'

export const useCartStore = defineStore('cart', () => {
  const cartItems = ref([])
  const cartCount = ref(0)

  async function fetchCartCount(userId) {
    try {
      const res = await api.getCartCount(userId)
      cartCount.value = res.data
    } catch (e) {
      console.error('获取购物车数量失败', e)
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