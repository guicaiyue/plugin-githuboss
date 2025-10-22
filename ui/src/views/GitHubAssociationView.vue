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
          <div style="padding: 0.5rem 1rem 0">
            <span class="ml-1 mb-1 block text-sm text-gray-500">
              关联后所加入的分组
            </span>
            <div
              class="mb-5 grid grid-cols-2 gap-x-2 gap-y-3 md:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-6"
            >
              <button
                type="button"
                class="inline-flex h-full w-full items-center gap-2 rounded-md border border-gray-200 bg-white px-3 py-2.5 text-sm font-medium text-gray-800 hover:bg-gray-50 hover:shadow-sm"
                v-for="(group, index) in [defaultGroup, ...customGroups]"
                :key="index"
                :class="{
                  '!bg-gray-100 shadow-sm':
                    group.metadata.name === selectedGroup,
                }"
                @click="selectedGroup = group.metadata.name"
              >
                <div
                  class="inline-flex w-full flex-1 gap-x-2 break-all text-left"
                >
                  <slot name="text">
                    {{ group?.spec.displayName }}
                  </slot>
                  <VStatusDot
                    v-if="group?.metadata.deletionTimestamp"
                    v-tooltip="$t('core.common.status.deleting')"
                    state="warning"
                    animate
                  />
                </div>
                <div class="flex-none">
                  <IconCheckboxCircle
                    v-if="group.metadata.name === selectedGroup"
                    class="text-primary"
                  />
                </div>
              </button>
            </div>
          </div>
          <VEntityContainer>
            <VEntity
              v-for="(file, index) in s3Objects.objects"
              :key="index"
              :is-selected="checkSelection(file)"
            >
              <template #checkbox>
                <input
                  v-model="selectedFiles"
                  :value="file.key || ''"
                  :disabled="file.isLinked"
                  type="checkbox"
                />
              </template>
              <template #start>
                <VEntityField>
                  <template #description>
                    <AttachmentFileTypeIcon
                      :display-ext="false"
                      :file-name="file.displayName || ''"
                      :width="8"
                      :height="8"
                    />
                  </template>
                </VEntityField>
                <VEntityField
                  :title="file.displayName || ''"
                  :description="file.key || ''"
                />
              </template>
              <template #end>
                <VEntityField>
                  <template #description>
                    <VTag :theme="file.isLinked ? 'default' : 'primary'">
                      {{ file.isLinked ? "已关联" : "未关联" }}
                    </VTag>
                  </template>
                </VEntityField>
                <VEntityField>
                  <template #description>
                    <VButton
                      :disabled="file.isLinked || false"
                      @click="selectOneAndLink(file)"
                    >
                      关联
                    </VButton>
                  </template>
                </VEntityField>
              </template>
            </VEntity>
          </VEntityContainer>
        </div>
      </Transition>

      <template #footer>
        <div class="bg-white sm:flex sm:items-center justify-between">
          <div class="inline-flex items-center gap-5">
            <span class="text-xs text-gray-500 hidden md:flex"
              >共 {{ s3Objects.objects?.length }} 项数据</span
            >
            <span class="text-xs text-gray-500 hidden md:flex"
              >已自动过滤文件夹对象，页面实际显示数量少为正常现象</span
            >
          </div>
          <div class="inline-flex items-center gap-5">
            <div class="inline-flex items-center gap-2">
              <VButton @click="handleFirstPage" :disabled="!policyName"
                >返回第一页</VButton
              >

              <span class="text-sm text-gray-500">第 {{ page }} 页</span>

              <VButton
                @click="handleNextPage"
                :disabled="!s3Objects.hasMore || isFetching || !policyName"
              >
                下一页
              </VButton>
            </div>
            <div class="inline-flex items-center gap-2">
              <select
                v-model="size"
                class="h-8 border outline-none rounded-base pr-10 border-solid px-2 text-gray-800 text-sm border-gray-300 page-size-select"
                @change="handleFirstPage"
              >
                <option
                  v-for="(sizeOption, index) in [20, 50, 100, 200]"
                  :key="index"
                  :value="sizeOption"
                >
                  {{ sizeOption }}
                </option>
              </select>
              <span class="text-sm text-gray-500">条/页</span>
            </div>
          </div>
        </div>
      </template>
    </VCard>
  </div>
  <VModal
    :visible="isShowModal"
    :fullscreen="false"
    :title="'关联结果'"
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
const isLinking = ref(false)
const linkTips = ref('')
const linkFailedTable = ref<Array<{objectKey: string, message: string}>>([])

// 策略选项
const policyOptions = ref<Array<{label: string, value: string}>>([])

// S3 对象数据
const s3Objects = ref<{
  objects?: Array<{
    key?: string
    displayName?: string
    isLinked?: boolean
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
    console.log(data.items);
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
    simpleStringControllerApi.listGitHubDirectoryContents({
      policyName: policyName.value
    }).then(response => {
      // handle response
      console.log(response.data)
    })
    // 模拟获取 S3 对象数据
    await new Promise(resolve => setTimeout(resolve, 500))

    const mockObjects = [
      {
        key: 'images/photo1.jpg',
        displayName: 'photo1.jpg',
        isLinked: Math.random() > 0.5
      },
      {
        key: 'images/photo2.png',
        displayName: 'photo2.png',
        isLinked: Math.random() > 0.5
      },
      {
        key: 'documents/readme.md',
        displayName: 'readme.md',
        isLinked: Math.random() > 0.5
      }
    ]

    // 根据筛选条件过滤数据
    let filteredObjects = mockObjects

    if (filePrefixBind.value) {
      filteredObjects = filteredObjects.filter(obj =>
        obj.key?.includes(filePrefixBind.value)
      )
    }

    if (selectedLinkedStatusItem.value !== 'all') {
      const isLinked = selectedLinkedStatusItem.value === 'linked'
      filteredObjects = filteredObjects.filter(obj => obj.isLinked === isLinked)
    }

    s3Objects.value = {
      objects: filteredObjects,
      hasMore: false
    }
  } catch (error) {
    console.error('获取 S3 对象失败:', error)
    s3Objects.value = { objects: [], hasMore: false }
  } finally {
    isFetching.value = false
  }
}

const handleLinkFiles = async () => {
  if (selectedFiles.value.length === 0) return

  isLinking.value = true
  linkTips.value = `正在关联 ${selectedFiles.value.length} 个文件...`
  linkFailedTable.value = []

  try {
    // 模拟关联操作
    await new Promise(resolve => setTimeout(resolve, 2000))

    // 模拟部分失败的情况
    const failedFiles = selectedFiles.value.slice(0, Math.floor(selectedFiles.value.length / 3))
    linkFailedTable.value = failedFiles.map(key => ({
      objectKey: key,
      message: '关联失败：文件已存在'
    }))

    linkTips.value = `关联完成！成功: ${selectedFiles.value.length - failedFiles.length}, 失败: ${failedFiles.length}`

    // 刷新数据
    await fetchS3Objects()
    selectedFiles.value = []
    checkedAll.value = false
  } catch (error) {
    console.error('关联文件失败:', error)
    linkTips.value = '关联失败，请重试'
  } finally {
    isLinking.value = false
  }
}

const handleCloseModal = () => {
  isShowModal.value = false
  linkTips.value = ''
  linkFailedTable.value = []
}

const handleRefresh = () => {
  fetchS3Objects()
}

const handleFirstPage = () => {
  page.value = 1
  fetchS3Objects()
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

// 生命周期
onMounted(() => {
  fetchPolicies()
  fetchGroups()
})
</script>

