# 注意事项
1、当前这个 UI 项目没有启用支持“任意值类名”的原子 CSS 框架（例如 Tailwind JIT、UnoCSS、Windi）。因此像 max-h-[90vh] 、 max-w-[90vw] 这样的类名不会生效，而直接写 style="max-height: 90vh" 会生效。