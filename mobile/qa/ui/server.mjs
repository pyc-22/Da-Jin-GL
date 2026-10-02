import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
const root=fileURLToPath(new URL('../../',import.meta.url))
const server=await createServer({root,configFile:false,plugins:[vue(),{name:'mobile-ui-fixtures',transformIndexHtml(html){return html.replace('/src/main.js','/qa/ui/bootstrap.js')}}],server:{host:'127.0.0.1',port:5190,strictPort:true}})
await server.listen();server.printUrls()
