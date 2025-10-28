import { definePlugin } from '@halo-dev/console-shared'
import GitHubAssociationView from './views/GitHubAssociationView.vue'
import { IconPlug, IconGitHub } from '@halo-dev/components'
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
  extensionPoints: {},
})
