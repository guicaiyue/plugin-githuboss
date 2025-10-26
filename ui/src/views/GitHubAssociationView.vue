<template>
  <VPageHeader title="GitHub 关联">
    <template #icon>
      <IconGitHub class="mr-2" />
    </template>
    <template #description>
      管理基于 GitHub OSS 存储附件管理
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VCard :body-class="['!p-0']">
      <template #header>
        <div class="block w-full bg-gray-50 px-4 py-3">
          <div
            class="relative flex flex-col flex-wrap items-start gap-4 sm:flex-row sm:items-center"
          >
            <div class="hidden items-center sm:flex">
              <input
                v-model="checkedAll"
                type="checkbox"
                @change="(event: Event) => handleCheckAllChange((event.target as HTMLInputElement).checked)"
              />
            </div>
            <div class="flex w-full flex-1 items-center sm:w-auto">
              <div
                v-if="!selectedFiles.length"
                class="flex flex-wrap items-center gap-2"
              >
                <span class="whitespace-nowrap">存储策略:</span>
                <FormKit
                  id="policyChoose"
                  outer-class="!p-0"
                  style="min-width: 10rem"
                  v-model="policyName"
                  name="policyName"
                  type="select"
                  :options="policyOptions"
                  @change="handleFirstPage"
                ></FormKit>
                <icon-error-warning v-if="!policyName" class="text-red-500" />
                <SearchInput
                  v-model="filePrefixBind"
                  v-if="policyName"
                  placeholder="请输入文件名前缀搜索"
                  @update:modelValue="handleFirstPage"
                ></SearchInput>
              </div>
              <VSpace v-else>
                <VButton type="primary" @click="handleLink"> 关联 </VButton>
                <VButton type="danger" @click="handleUnlink"> 取消关联 </VButton>
              </VSpace>
            </div>
            <VSpace spacing="lg" class="flex-wrap">
              <FilterCleanButton
                v-if="selectedLinkedStatusItem != linkedStatusItems[0].value"
                @click="selectedLinkedStatusItem = linkedStatusItems[0].value"
              />
              <FilterDropdown
                v-model="selectedLinkedStatusItem"
                :label="$t('core.common.filters.labels.status')"
                :items="linkedStatusItems"
              />

              <div class="flex flex-row gap-2">
                <div
                  class="group cursor-pointer rounded p-1 hover:bg-gray-200"
                  @click="fetchObjects()"
                >
                  <IconRefreshLine
                    v-tooltip="$t('core.common.buttons.refresh')"
                    :class="{
                      'animate-spin text-gray-900': isFetching,
                    }"
                    class="h-4 w-4 text-gray-600 group-hover:text-gray-900"
                  />
                </div>
              </div>
            </VSpace>
          </div>
        </div>
      </template>

      <VLoading v-if="isFetching" />

      <Transition v-else-if="!s3Objects.objects?.length" appear name="fade">
        <VEmpty message="空空如也" :title="emptyTips"> </VEmpty>
      </Transition>

      <Transition v-else appear name="fade">
        <div class="box-border h-full w-full">
          <div class="px-4">
            <div class="mb-3 flex items-center justify-between mt-2">
              <div class="text-xs text-gray-600 break-all">当前目录：{{ currentPath || '/' }}</div>
              <div class="inline-flex items-center gap-2">
                <VButton @click="goParent" :disabled="currentPath === rootPath">返回上级</VButton>
                <VButton @click="goRoot" :disabled="currentPath === rootPath">回到根目录</VButton>
              </div>
            </div>
            <div class="masonry">
              <AttachmentCard
                class="masonry-item"
                v-for="(file, index) in s3Objects.objects"
                :key="file.key || index"
                :title="file.displayName || ''"
                :description="file.key || ''"
                :image-src="file.downloadUrl || ''"
                :linked="file.isLinked || false"
                :selected="checkSelection(file)"
                :disabled="file.type !== 'file'"
                :is-directory="file.type === 'dir'"
                @open="enterDirectory(file)"
                @toggle-select="handleSelectFile(file.key || '')"
                @link="selectOneAndLink(file)"
                @unlink="selectOneAndUnlink(file)"
              />
            </div>
          </div>
        </div>
      </Transition>
      <template #footer></template>
    </VCard>
  </div>

  <!-- 取消关联确认弹窗 -->
  <VModal
    :visible="isConfirmUnlink"
    :fullscreen="false"
    title="取消关联确认"
    :width="500"
    :mount-to-body="true"
    @close="handleCloseConfirmUnlink"
  >
    <template #footer>
      <VSpace>
        <VButton type="primary" @click="performUnlink(true)">仅删 Halo</VButton>
        <VButton type="danger" @click="performUnlink(false)">同步删除 GitHub</VButton>
        <VButton @click="handleCloseConfirmUnlink">关闭窗口</VButton>
      </VSpace>
    </template>
    <div class="flex flex-col">
      确认取消关联以下 {{ selectedFiles.length }} 个文件？
    </div>
  </VModal>

  <!-- 结果弹窗 -->
  <VModal
    :visible="isShowModal"
    :fullscreen="false"
    :title="modalTitle"
    :width="500"
    :mount-to-body="true"
    @close="handleModalClose"
  >
    <template #footer>
      <VSpace>
        <VButton :loading="isLinking" type="primary" @click="handleModalClose">
          确定
        </VButton>
      </VSpace>
    </template>
    <div class="flex flex-col">
      {{ linkTips }}
      <table v-if="linkFailedTable.length != 0">
        <tr>
          <th class="border border-black font-normal">失败对象</th>
          <th class="border border-black font-normal">失败原因</th>
        </tr>
        <tr v-for="failedInfo in linkFailedTable" :key="failedInfo.objectKey">
          <th class="border border-black font-normal">
            {{ failedInfo.objectKey }}
          </th>
          <th class="border border-black font-normal">
            {{ failedInfo.message }}
          </th>
        </tr>
      </table>
    </div>
  </VModal>
</template>

<style lang="scss" scoped>
.page-size-select:focus {
  --tw-border-opacity: 1;
  border-color: rgba(var(--colors-primary), var(--tw-border-opacity));
}
</style>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  VPageHeader,
  VCard,
  VButton,
  VSpace,
  VLoading,
  VAlert,
  VEmpty,
  VTag,
  VModal,
  VStatusDot,
  VEntity,
  VEntityField,
  VEntityContainer,
  IconGitHub,
  IconCheckboxCircle,
  IconRefreshLine
} from '@halo-dev/components'
import { coreApiClient,axiosInstance  } from '@halo-dev/api-client'
import { SimpleStringControllerApi } from '@/api'
import AttachmentCard from '@/components/AttachmentCard.vue'

var simpleStringControllerApi = new SimpleStringControllerApi(
  undefined,
  axiosInstance.defaults.baseURL,
  axiosInstance
);

// 路由
const router = useRouter()

// 响应式数据
const checkedAll = ref(false)
const selectedFiles = ref<string[]>([])
const policyName = ref('')
const filePrefixBind = ref('')
const selectedLinkedStatusItem = ref('all')
const isFetching = ref(false)
const selectedGroup = ref('')
const page = ref(1)
const size = ref(20)
const isShowModal = ref(false)
const isConfirmUnlink = ref(false)
const isLinking = ref(false)
const linkTips = ref('')
const linkFailedTable = ref<Array<{objectKey: string, message: string}>>([])
const rootPath = ref('')
const currentPath = ref('')
const showFilesOnly = ref(false)
const modalTitle = ref('关联结果')

// 策略选项
const policyOptions = ref<Array<{label: string, value: string}>>([])

// S3 对象数据
const s3Objects = ref<{
  objects?: Array<{
    key?: string
    displayName?: string
    downloadUrl?: string
    type?: string
    isLinked?: boolean
    path?: string
    sha?: string
    size?: number
  }>
  hasMore?: boolean
}>({})

// 分组数据
const defaultGroup = ref({
  metadata: { name: 'default', deletionTimestamp: undefined as string | undefined },
  spec: { displayName: '默认分组' }
})

const customGroups = ref<Array<{
  metadata: { name: string, deletionTimestamp?: string }
  spec: { displayName: string }
}>>([])

// 链接状态选项
const linkedStatusItems = ref([
  { label: '全部', value: 'all' },
  { label: '已关联', value: 'linked' },
  { label: '未关联', value: 'unlinked' }
])

// 计算属性
const emptyTips = computed(() => {
  if (!policyName.value) {
    return '请先选择存储策略'
  }
  return '暂无数据'
})

// 方法函数
const onPolicyChange = (event: Event) => {
  const target = event.target as HTMLSelectElement
  policyName.value = target.value
  // 重置数据
  selectedFiles.value = []
  checkedAll.value = false
  page.value = 1
  // 获取数据
  fetchS3Objects()
}

const onLinkedStatusChange = (value: string) => {
  selectedLinkedStatusItem.value = value
  page.value = 1
  fetchS3Objects()
}

const onGroupChange = (value: string) => {
  selectedGroup.value = value
}

const onFilePrefixChange = (value: string) => {
  filePrefixBind.value = value
  page.value = 1
  fetchS3Objects()
}

const onClearFilters = () => {
  filePrefixBind.value = ''
  selectedLinkedStatusItem.value = 'all'
  selectedGroup.value = ''
  page.value = 1
  fetchS3Objects()
}

const handleCheckAllChange = (checked: boolean) => {
  checkedAll.value = checked
  if (checked) {
    selectedFiles.value = s3Objects.value.objects?.map(obj => obj.key || '') || []
  } else {
    selectedFiles.value = []
  }
}

const handleSelectFile = (objectKey: string) => {
  const index = selectedFiles.value.indexOf(objectKey)
  if (index > -1) {
    selectedFiles.value.splice(index, 1)
  } else {
    selectedFiles.value.push(objectKey)
  }

  // 更新全选状态
  const totalFiles = s3Objects.value.objects?.length || 0
  checkedAll.value = selectedFiles.value.length === totalFiles && totalFiles > 0
}

const fetchPolicies = async () => {
  try {
    // 使用 coreApiClient 获取所有存储策略列表
    const { data } = await coreApiClient.storage.policy.listPolicy()
    // 过滤出 GitHub OSS 策略并转换为选项格式
    policyOptions.value = data.items
      .filter(policy => policy.spec?.templateName === 'githuboss-policy-template')
      .map(policy => ({
        label: policy.spec?.displayName || policy.metadata?.name || '未命名策略',
        value: policy.metadata?.name || ''
      }))
    // 如果没有找到 GitHub OSS 策略，显示提示
    if (policyOptions.value.length === 0) {
      policyOptions.value = [
        { label: '请先创建 GitHub OSS 存储策略', value: '' }
      ]
    }
  } catch (error) {
    console.error('获取存储策略失败:', error)
    // 出错时显示错误提示
    policyOptions.value = [
      { label: '获取策略失败，请刷新重试', value: '' }
    ]
  }
}

const fetchGroups = async () => {
  try {
    // 模拟获取分组数据
    customGroups.value = [
      {
        metadata: { name: 'images' },
        spec: { displayName: '图片分组' }
      },
      {
        metadata: { name: 'documents' },
        spec: { displayName: '文档分组' }
      }
    ]
  } catch (error) {
    console.error('获取分组失败:', error)
  }
}

const fetchS3Objects = async () => {
  if (!policyName.value) return

  isFetching.value = true
  try {
    const [haloResp, ghResp] = await Promise.all([
      simpleStringControllerApi.listGitHubHaloAttachments({
        policyName: policyName.value
      }),
      simpleStringControllerApi.listGitHubAttachments({
        policyName: policyName.value,
        path: currentPath.value
      })
    ])

    const rawData = typeof ghResp.data === 'string' ? JSON.parse(ghResp.data) : ghResp.data
    const haloMap: Record<string, string> = (haloResp?.data as any) || {}

    let items = Array.isArray(rawData) ? rawData : []

    // 排序：目录在前，文件在后；同类型按名称升序
    items.sort((a: any, b: any) => {
      const aType = a?.type || ''
      const bType = b?.type || ''
      if (aType !== bType) return aType === 'dir' ? -1 : 1
      const aName = a?.name || a?.path || ''
      const bName = b?.name || b?.path || ''
      return aName.localeCompare(bName)
    })

    let mapped = items.map((item: any) => ({
      key: item?.path || item?.name || '',
      displayName: item?.name || item?.path || '',
      downloadUrl: item?.download_url || '',
      type: item?.type || '',
      path: item?.path || '',
      sha: item?.sha || '',
      size: typeof item?.size === 'number' ? item.size : 0,
      isLinked: !!haloMap[(item?.sha+item?.path || '')]
    }))

    if (filePrefixBind.value) {
      mapped = mapped.filter(obj => (obj.key || '').includes(filePrefixBind.value))
    }

    if (selectedLinkedStatusItem.value !== 'all') {
      const isLinkedFilter = selectedLinkedStatusItem.value === 'linked'
      mapped = mapped.filter(obj => obj.isLinked === isLinkedFilter)
    }

    if (showFilesOnly.value) {
      mapped = mapped.filter(obj => obj.type === 'file')
    }

    s3Objects.value = {
      objects: mapped,
      hasMore: false
    }
  } catch (error) {
    console.error('获取 GitHub 附件失败:', error)
    s3Objects.value = { objects: [], hasMore: false }
  } finally {
    isFetching.value = false
  }
}

const handleLinkFiles = async () => {
  if (selectedFiles.value.length === 0 || !policyName.value) return

  isLinking.value = true
  isShowModal.value = true
  modalTitle.value = '关联结果'
  linkTips.value = `正在关联 ${selectedFiles.value.length} 个文件...`
  linkFailedTable.value = []

  try {
    const payload = selectedFiles.value
      .map(key => {
        const item = s3Objects.value.objects?.find(o => (o.key || '') === key)
        if (!item) return null
        return {
          policyName: policyName.value,
          path: item.path || key,
          sha: item.sha || '',
          size: item.size ?? 0
        }
      })
      .filter(Boolean) as Array<{
        policyName: string
        path: string
        sha: string
        size: number
      }>

    const { data } = await simpleStringControllerApi.linkGitHubAttachment({ linkReqObject: payload })

    const save = Number(data?.saveCount ?? 0)
    const fail = Number(data?.failCount ?? 0)
    const err = data?.firstErrorMsg ?? ''

    linkTips.value = `关联完成！成功: ${save}, 失败: ${fail}${err ? `，首个错误: ${err}` : ''}`

    if (fail > 0) {
      linkFailedTable.value = [
        {
          objectKey: `共 ${fail} 项`,
          message: err || '未知错误'
        }
      ]
    } else {
      linkFailedTable.value = []
    }

    await fetchS3Objects()
    selectedFiles.value = []
    checkedAll.value = false
  } catch (error: any) {
    console.error('关联文件失败:', error)
    linkTips.value = `关联失败：${error?.message || '未知错误'}`
    linkFailedTable.value = [
      { objectKey: '请求失败', message: error?.message || '未知错误' }
    ]
  } finally {
    isLinking.value = false
  }
}

const handleUnlinkFiles = async (unLinked: boolean) => {
  if (selectedFiles.value.length === 0 || !policyName.value) return

  isLinking.value = true
  isShowModal.value = true
  modalTitle.value = '取消关联结果'
  linkTips.value = `正在取消关联 ${selectedFiles.value.length} 个文件...`
  linkFailedTable.value = []

  try {
    const items = selectedFiles.value
      .map(key => {
        const item = s3Objects.value.objects?.find(o => (o.key || '') === key)
        if (!item) return null
        return {
          path: item.path || key,
          sha: item.sha || '',
          size: item.size ?? 0
        }
      })
      .filter(Boolean) as Array<{ path: string; sha: string; size: number }>

    const reqPayload = {
      policyName: policyName.value,
      unLinked,
      unlinkObjectList: items
    }

    const { data } = await simpleStringControllerApi.unlinkGitHubAttachment({ unlinkReqObject: reqPayload })

    const del = Number(data?.delCount ?? 0)
    const fail = Number(data?.failCount ?? 0)
    const err = data?.firstErrorMsg ?? ''

    linkTips.value = `取消关联完成！删除: ${del}, 失败: ${fail}${err ? `，首个错误: ${err}` : ''}`

    if (fail > 0) {
      linkFailedTable.value = [
        {
          objectKey: `共 ${fail} 项`,
          message: err || '未知错误'
        }
      ]
    } else {
      linkFailedTable.value = []
    }

    await fetchS3Objects()
    selectedFiles.value = []
    checkedAll.value = false
  } catch (error: any) {
    console.error('取消关联失败:', error)
    linkTips.value = `取消关联失败：${error?.message || '未知错误'}`
    linkFailedTable.value = [
      { objectKey: '请求失败', message: error?.message || '未知错误' }
    ]
  } finally {
    isLinking.value = false
  }
}

const selectOneAndUnlink = async (file: any) => {
  if (!file.key) return
  selectedFiles.value = [file.key]
  isConfirmUnlink.value = true
}

const performUnlink = async (onlyHalo: boolean) => {
  isConfirmUnlink.value = false
  await handleUnlinkFiles(onlyHalo)
}

const handleCloseConfirmUnlink = () => {
  isConfirmUnlink.value = false
}

const handleRefresh = () => {
  fetchS3Objects()
}

const handleFirstPage = () => {
  page.value = 1
  fetchS3Objects()
  simpleStringControllerApi.getGitHubRootPath({
    policyName: policyName.value
  }).then((resp) => {
    rootPath.value = resp.data || ''
    currentPath.value = rootPath.value
  })
}

const handleModalClose = () => {
  isShowModal.value = false
  linkTips.value = ''
  linkFailedTable.value = []
}

const checkSelection = (file: any) => {
  return selectedFiles.value.includes(file.key || '')
}

const selectOneAndLink = async (file: any) => {
  if (!file.key) return

  selectedFiles.value = [file.key]
  isShowModal.value = true
  await handleLinkFiles()
}

const handleNextPage = () => {
  page.value += 1
  fetchS3Objects()
}

const enterDirectory = (file: any) => {
  if (file?.type === 'dir') {
    currentPath.value = file.path || file.key || ''
    fetchS3Objects()
  }
}

const goParent = () => {
  const p = currentPath.value || ''
  if (!p) return
  const parts = p.split('/').filter(Boolean)
  parts.pop()
  currentPath.value = parts.join('/')
  fetchS3Objects()
}

const goRoot = () => {
  if (currentPath.value === rootPath.value) return
  currentPath.value = rootPath.value
  fetchS3Objects()
}

const fetchObjects = () => {
  fetchS3Objects()
}

// 模拟国际化函数
const $t = (key: string) => {
  const translations: Record<string, string> = {
    'core.common.filters.labels.status': '状态',
    'core.common.buttons.refresh': '刷新',
    'core.common.status.deleting': '删除中'
  }
  return translations[key] || key
}

const handleLink = () => {
  if (selectedFiles.value.length === 0) return
  isShowModal.value = true
  handleLinkFiles()
}

const handleUnlink = () => {
  if (selectedFiles.value.length === 0) return
  isConfirmUnlink.value = true
}

// 生命周期
onMounted(() => {
  fetchPolicies()
  fetchGroups()
})
</script>


<style scoped>
/* Masonry（瀑布流）布局：根据列宽自动分列，避免大屏拉长 */
.masonry {
  column-gap: 12px;
  column-fill: balance;
  /* 默认列宽（超小屏） */
  column-width: 160px;
}
.masonry-item {
  break-inside: avoid;
  margin-bottom: 12px;
}
/* Tailwind 常用断点：sm:640, md:768, lg:1024, xl:1280, 2xl:1536 */
@media (min-width: 640px) {
  .masonry { column-width: 180px; }
}
@media (min-width: 768px) {
  .masonry { column-width: 220px; }
}
@media (min-width: 1024px) {
  .masonry { column-width: 260px; }
}
@media (min-width: 1280px) {
  .masonry { column-width: 300px; }
}
@media (min-width: 1536px) {
  .masonry { column-width: 320px; }
}
</style>

