import { useAuthStore } from '../stores/auth.js'

export const permission = {
  mounted(element, binding) {
    element.hidden = !useAuthStore().can(binding.value)
  },
  updated(element, binding) {
    element.hidden = !useAuthStore().can(binding.value)
  }
}
