import { http } from './request.js'
import { getStorage } from '../utils/storage.js'

export function uploadImage(filePath, file, meta = {}) {
  if (file?.size > 5 * 1024 * 1024) return Promise.reject(new Error('照片超过5MB，请压缩或重新拍照'))
  if (typeof uni !== 'undefined' && uni.uploadFile) {
    return new Promise((resolve, reject) => uni.uploadFile({ url: `${http.defaults.baseURL}/api/upload`, filePath, name: 'file', formData: meta, header: { Authorization: `Bearer ${getStorage('dajin-token', '')}` }, success: (res) => { try { const body = JSON.parse(res.data); if (res.statusCode < 200 || res.statusCode >= 300 || (body.code !== undefined && body.code !== 200)) return reject(new Error(body.message || '照片上传失败')); resolve(body) } catch (e) { reject(e) } }, fail: reject }))
  }
  const form = new FormData(); form.append('file', file); Object.entries(meta).forEach(([key, value]) => { if (value != null && value !== '') form.append(key, String(value)) })
  return http.post('/api/upload', form, { timeout: 60000 }).catch(error => {
    if (error?.response?.status === 413) error.message = '照片超过服务器上传限制，请联系管理员检查配置'
    throw error
  })
}
