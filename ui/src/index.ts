import { definePlugin } from '@halo-dev/console-shared'
import HomeView from './views/HomeView.vue'
import GitHubAssociationView from './views/GitHubAssociationView.vue'
import { IconPlug, IconGitHub } from '@halo-dev/components'
import { markRaw } from 'vue'

export default definePlugin({
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/example',
        name: 'Example',
        component: HomeView,
        meta: {
          title: '示例页面',
          searchable: true,
          menu: {
            name: '示例页面',
            group: '示例分组',
            icon: markRaw(IconPlug),
            priority: 0,
          },
        },
      },
    },
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
  extensionPoints: {},
})
