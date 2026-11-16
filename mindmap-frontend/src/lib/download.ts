/**
 * 浏览器端文件下载工具（阶段 6A）。
 * 纯前端实现，不请求后端，不改动服务器数据。
 */

const WINDOWS_INVALID_CHARS = /[\\/:*?"<>|]/g

/** 把笔记标题清洗成安全的文件名（不含扩展名）；为空时使用 fallback。 */
export function sanitizeFileName(name: string, fallback: string): string {
  const cleaned = (name ?? '')
    .normalize('NFC')
    .replace(WINDOWS_INVALID_CHARS, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 120)
    .trim()
  return cleaned.length > 0 ? cleaned : fallback
}

function triggerBrowserDownload(url: string, fileName: string): void {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
}

/** 下载文本内容（例如 Markdown 笔记）。 */
export function downloadTextFile(
  fileName: string,
  content: string,
  mime = 'text/markdown;charset=utf-8'
): void {
  const blob = new Blob([content], { type: mime })
  const url = URL.createObjectURL(blob)
  triggerBrowserDownload(url, fileName)
  // 浏览器已经开始下载后再释放
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** 通过 data URL（如 PNG / SVG base64）触发下载。 */
export function downloadDataUrl(dataUrl: string, fileName: string): void {
  triggerBrowserDownload(dataUrl, fileName)
}