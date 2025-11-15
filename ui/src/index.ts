import { definePlugin } from '@halo-dev/console-shared'
import GitHubAssociationView from './views/GitHubAssociationView.vue'
import { IconPlug, IconGitHub } from '@halo-dev/components'
import NetworkTab from './views/NetworkTab.vue'
import { markRaw } from 'vue'

export default definePlugin({
  routes: [
    {
      parentName: 'ToolsRoot',
      route: {
        path: '/github-association',
        name: 'GitHubAssociation',
        component: GitHubAssociationView,
        meta: {
          title: 'GitHub 关联',
          searchable: true,
          menu: {
            name: 'GitHub 关联',
            group: '工具',
            icon: markRaw(IconGitHub),
            priority: 10,
          },
        },
      },
    }
  ],
    // 扩展插件详情页的选项卡，使网络面板在插件详情页也可访问
  // 参考文档：plugin:self:tabs:create
  extensionPoints: {
    // 在插件详情页添加一个名为“网络”的选项卡
    'plugin:self:tabs:create': () => {
      return [
        {
          id: 'github-network-tab', // 选项卡唯一标识
          label: '网络', // 显示名称
          // 使用 markRaw 防止组件被深度代理，提升渲染稳定性
          component: markRaw(NetworkTab),
          // 若需要权限控制，可在此添加权限标识，例如：['plugin:plugin-githuboss:view']
          permissions: []
        }
      ]
    }
  },
})
