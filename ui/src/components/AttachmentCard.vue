<template>
  <div class="border rounded-md bg-white overflow-hidden hover:shadow-sm flex flex-col">
    <div class="relative bg-gray-100">
      <input
        type="checkbox"
        class="absolute right-1 top-1 h-4 w-4"
        :checked="selected"
        :disabled="disabled || linked"
        @change="$emit('toggle-select')"
      />
      <img v-if="isImage && !isDirectory" :src="imageSrc" alt="" class="w-full h-auto object-cover cursor-zoom-in" @click="openPreview" />
      <div v-else-if="isDirectory" class="flex h-32 items-center justify-center text-yellow-500 cursor-pointer" @click="$emit('open')">
        <svg class="h-12 w-12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8l-2-2z" />
        </svg>
      </div>
      <div v-else class="flex h-32 items-center justify-center text-gray-500">
        <span class="text-sm">{{ ext.toUpperCase() || 'FILE' }}</span>
      </div>
    </div>
    <div class="px-3 py-2">
      <p class="text-sm text-gray-800 truncate">{{ title }}</p>
    </div>
    <div class="px-3 py-2 flex items-center justify-between">
      <VTag :theme="linked ? 'default' : 'primary'">
        {{ linked ? '已关联' : '未关联' }}
      </VTag>
      <VButton :disabled="linked || disabled" @click="$emit('link')">关联</VButton>
    </div>
  </div>
  <!-- 图片预览遮罩 -->
  <div
    v-if="isPreviewVisible"
    class="fixed inset-0 flex items-center justify-center z-50 bg-black bg-opacity-50" style="background-color: rgba(0,0,0,0.5);"
    @click.self="closePreview"
  >
    <div class="relative bg-white rounded-md shadow-lg p-2 flex items-center justify-center">
      <button
        class="absolute right-2 top-2 rounded-full bg-gray-800/80 text-white px-2 py-1 text-xs"
        @click="closePreview"
      >
        关闭
      </button>
      <img :src="imageSrc" alt="" style="max-width: 90vw; max-height: 90vh; object-fit: contain;" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { VTag, VButton } from '@halo-dev/components'

const props = defineProps<{
  title: string
  description: string
  imageSrc?: string
  linked?: boolean
  selected?: boolean
  disabled?: boolean
  isDirectory?: boolean
}>()

const isImage = computed(() => !!props.imageSrc && /\.(png|jpe?g|webp|gif|bmp|svg)$/i.test(props.imageSrc))

const ext = computed(() => {
  const name = props.title || props.description || ''
  const parts = name.split('.')
  return parts.length > 1 ? parts.pop() || '' : ''
})

const isPreviewVisible = ref(false)
const openPreview = () => {
  if (isImage.value && props.imageSrc && !props.isDirectory) {
    isPreviewVisible.value = true
  }
}
const closePreview = () => {
  isPreviewVisible.value = false
}
</script>
