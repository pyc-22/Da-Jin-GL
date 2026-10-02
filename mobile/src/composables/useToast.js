import { ref } from 'vue'
const message = ref('')
let timer
export function useToast() {
  function dismiss() { clearTimeout(timer); message.value = '' }
  function toast(text, duration = 3200) {
    clearTimeout(timer)
    message.value = String(text || '')
    timer = setTimeout(dismiss, duration)
  }
  return { message, toast, dismiss }
}
