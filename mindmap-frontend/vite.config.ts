import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 拆包策略（2026-10-29 实测结论，改之前先读这段）：
//
// 1) 页面用 React.lazy 动态 import，Rollup 会沿动态边界自动切分；
//    只靠它会出问题：codemirror（约 600kB）被并进了 HomePage chunk，
//    而首页是登录后的默认界面，等于每个用户都白下编辑器。
// 2) 用**对象形式**的 manualChunks（按包名列表）也不可靠：实测 react-dom 与
//    scheduler 被归到了 @xyflow 那个包里，index.html 于是直接 modulepreload 了
//    628kB 编辑器包 + 319kB 流向包，首屏从 1.06MB 涨到约 1.9MB。
// 3) 所以这里用**函数形式**，按模块真实路径分组。改完必须检查两件事：
//    - dist/index.html 的 modulepreload 列表里只能有 vendor 与入口，不能有 editor / flow
//    - 各 chunk 里不能再出现别的库的代码（用关键字粗查）
const vendorGroups: Array<[string, RegExp]> = [
  // 编辑器：只有笔记页用
  ['editor', /[\\/]node_modules[\\/](@codemirror|@lezer|@uiw|crelt|style-mod|w3c-keyname)[\\/]/],
  // 思维导图渲染：只有首页用。注意**不要把 zustand 放进来**——它是被入口静态
  // 依赖的状态库，一旦归进 flow，整个 flow 包就会出现在 index.html 的预载列表里。
  ['flow', /[\\/]node_modules[\\/](@xyflow|d3-[a-z]+|classcat)[\\/]/],
  // 自动布局引擎：只有首页用（真正的重活在 elk-worker 里，按需加载）
  ['elk', /[\\/]node_modules[\\/]elkjs[\\/]/]
]

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5273,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          for (const [name, pattern] of vendorGroups) {
            if (pattern.test(id)) return name
          }
          // 其余第三方（react / react-dom / scheduler 等）统一进 vendor：
          // 它们被入口静态依赖，提前加载是应该的。
          return 'vendor'
        }
      }
    }
  }
})
