<template>
  <div class="border rounded-md bg-white overflow-hidden hover:shadow-sm flex flex-col">
    <div class="relative h-32 bg-gray-100">
      <input
        type="checkbox"
        class="absolute right-2 top-2 h-4 w-4"
        :checked="selected"
        :disabled="disabled || linked"
        @change="$emit('toggle-select')"
      />
      <img v-if="isImage" :src="imageSrc" alt="" class="w-full h-full object-cover" />
      <div v-else class="flex h-full items-center justify-center text-gray-500">
        <span class="text-sm">{{ ext.toUpperCase() || 'FILE' }}</span>
      </div>
    </div>
    <div class="px-3 py-2">
      <p class="text-sm text-gray-800 truncate">{{ title }}</p>
      <p class="text-xs text-gray-500 truncate">{{ description }}</p>
    </div>
    <div class="px-3 py-2 flex items-center justify-between">
      <VTag :theme="linked ? 'default' : 'primary'">
        {{ linked ? '已关联' : '未关联' }}
      </VTag>
      <VButton :disabled="linked || disabled" @click="$emit('link')">关联</VButton>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { VTag, VButton } from '@halo-dev/components'

const props = defineProps<{
  title: string
  description: string
  imageSrc?: string
  linked?: boolean
  selected?: boolean
  disabled?: boolean
}>()

const isImage = computed(() => !!props.imageSrc && /\.(png|jpe?g|webp|gif|bmp|svg)$/i.test(props.imageSrc))

const ext = computed(() => {
  const name = props.title || props.description || ''
  const parts = name.split('.')
  return parts.length > 1 ? parts.pop() || '' : ''
})
</script>