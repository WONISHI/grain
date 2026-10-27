<script setup lang="ts">
import { computed } from 'vue'
import { fileIconUrl } from '../icons/material'

const props = defineProps<{
  path: string
}>()

const src = computed(() => fileIconUrl(props.path))
</script>

<template>
  <img class="file-icon" :src="src" width="16" height="16" alt="" aria-hidden="true" />
</template>
