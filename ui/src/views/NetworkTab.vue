<template>
  <VPageHeader title="网络">
    <template #description>代理配置与 GitHub 连通性测试</template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VCard>
      <template #header>
        <div class="w-full bg-gray-50 px-4 py-3">代理配置</div>
      </template>
      <div class="flex flex-col gap-3">
        <FormKit
          type="text"
          name="proxyPath"
          label="代理地址"
          help="例如：http://127.0.0.1:7890 或 socks://127.0.0.1:7891"
          v-model="form.proxyPath"
        />
        <FormKit type="radio" name="enabled" label="启用代理" v-model="form.enabled" :options="[
          { label: '开启', value: true },
          { label: '关闭', value: false }
        ]" />
        <FormKit type="number" name="timeoutMs" label="超时(毫秒)" v-model="form.timeoutMs" />
        <div>
          <VButton type="primary" :loading="saving" @click="saveProxy">保存</VButton>
        </div>
      </div>
    </VCard>

    <div class="mt-4"></div>
    <VCard>
      <template #header>
        <div class="w-full bg-gray-50 px-4 py-3">连通性测试</div>
      </template>
      <div class="flex items-center gap-3">
        <VButton type="primary" :loading="testing" @click="runTest">开始测试</VButton>
        <div class="text-xs text-gray-600">默认超时 {{ form.timeoutMs }} ms</div>
      </div>
      <div class="mt-3" v-for="item in results" :key="item?.host">
        <div class="result-card">
          <div class="result-row">
            <div class="result-key">域名</div>
            <div class="result-val">{{ item?.host ?? '' }}</div>
          </div>
          <div class="result-row">
            <div class="result-key">DNS解析IP</div>
            <div class="result-val">{{ (item?.ips ?? []).join(', ') || '无' }}</div>
          </div>
          <div class="result-row" v-if="item?.dnsError">
            <div class="result-key">DNS错误</div>
            <div class="result-val error">{{ item?.dnsError }}</div>
          </div>
          <div class="result-row">
            <div class="result-key">HTTP状态码</div>
            <div class="result-val">{{ item?.httpStatus ?? -1 }}</div>
          </div>
          <div class="result-row">
            <div class="result-key">HTTP延迟(ms)</div>
            <div class="result-val">{{ item?.httpLatencyMs ?? 0 }}</div>
          </div>
          <div class="result-row">
            <div class="result-key">是否成功</div>
            <div class="result-val" :class="item?.success ? 'ok' : 'error'">{{ item?.success ? '是' : '否' }}</div>
          </div>
          <div class="result-row" v-if="item?.error">
            <div class="result-key">错误信息</div>
            <div class="result-val error">{{ item?.error }}</div>
          </div>
        </div>
      </div>
    </VCard>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { VPageHeader, VCard, VButton } from '@halo-dev/components'
import { axiosInstance } from '@halo-dev/api-client'
import { AttachmentsControllerApi } from '@/api'
import type { NetworkTestItem } from '@/api'
var attachmentsController = new AttachmentsControllerApi(
  undefined,
  axiosInstance.defaults.baseURL,
  axiosInstance
);

const form = ref<{ proxyPath: string; enabled: boolean; timeoutMs: number }>({
  proxyPath: '',
  enabled: false,
  timeoutMs: 10000,
})
const saving = ref(false)
const testing = ref(false)
const results = ref<NetworkTestItem[]>([])

const fetchProxy = async () => {
  const { data } = await attachmentsController.getProxy()
  form.value.proxyPath = data?.proxyPath || ''
  form.value.enabled = !!data?.enabled
  form.value.timeoutMs = Number(data?.timeoutMs ?? 10000)
}

const saveProxy = async () => {
  saving.value = true
  try {
    await attachmentsController.saveProxy({
      networkConfig: form.value
    })
  } finally {
    saving.value = false
  }
}

const runTest = async () => {
  testing.value = true
  try {
    const { data } = await attachmentsController.networkTest()
    results.value = data
  } finally {
    testing.value = false
  }
}

const pretty = (obj: any) => {
  try { return JSON.stringify(obj ?? {}, null, 2) } catch { return String(obj ?? '') }
}

onMounted(() => {
  fetchProxy()
})
</script>

<style scoped>
.result-card { background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px; }
.result-row { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 6px; }
.result-key { width: 120px; color: #64748b; font-size: 12px; }
.result-val { flex: 1; font-size: 12px; word-break: break-all; }
.ok { color: #16a34a; }
.error { color: #dc2626; }
pre {
  background: #f8fafc;
  padding: 8px;
  border-radius: 6px;
}
</style>
