<script setup lang="ts">
import { computed, ref, watch } from 'vue'

export interface DomAttr {
  name: string
  value: string
}

export interface DomNode {
  tag: string
  attrs?: DomAttr[]
  text?: string
  badges?: string[]
  children?: DomNode[]
  error?: string
}

const props = withDefaults(defineProps<{
  node: DomNode
  depth?: number
  selectedId?: string
  nodeId?: string
}>(), {
  depth: 0,
})

const emit = defineEmits<{
  select: [id: string]
}>()

const expanded = ref(props.depth < 3)
const id = computed(() => props.nodeId || `n-${props.depth}`)
const kids = computed(() => props.node.children || [])
const hasKids = computed(() => kids.value.length > 0)
const isSelected = computed(() => props.selectedId === id.value)
const isVoid = computed(() => /^(area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)$/i.test(props.node.tag))

watch(
  () => props.selectedId,
  (selected) => {
    if (!selected) return
    if (selected === id.value || selected.startsWith(`${id.value}.`)) {
      expanded.value = true
    }
  },
  { immediate: true },
)

function toggle() {
  if (hasKids.value) expanded.value = !expanded.value
}

function onSelect() {
  emit('select', id.value)
}
</script>

<template>
  <div class="dt-node" :class="{ on: isSelected }" :data-depth="depth" :data-node-id="id">
    <div class="dt-line" :style="{ paddingLeft: `${8 + depth * 12}px` }" @click="onSelect">
      <button
        v-if="hasKids"
        type="button"
        class="dt-twist"
        :class="{ open: expanded }"
        :aria-expanded="expanded"
        aria-label="展开或折叠"
        @click.stop="toggle"
      ></button>
      <span v-else class="dt-twist spacer"></span>

      <template v-if="node.tag === '#text'">
        <span class="dt-text">"{{ node.text }}"</span>
      </template>
      <template v-else-if="node.tag === '#comment'">
        <span class="dt-comment">&lt;!-- {{ node.text }} --&gt;</span>
      </template>
      <template v-else>
        <span class="dt-punct">&lt;</span>
        <span class="dt-tag">{{ node.tag }}</span>
        <template v-for="(attr, index) in node.attrs || []" :key="`${attr.name}-${index}`">
          <span class="dt-space"> </span>
          <span class="dt-attr">{{ attr.name }}</span>
          <span class="dt-punct">="</span>
          <span class="dt-value">{{ attr.value }}</span>
          <span class="dt-punct">"</span>
        </template>
        <span class="dt-punct">{{ isVoid && !hasKids ? ' /&gt;' : '&gt;' }}</span>
        <span v-if="!expanded && hasKids" class="dt-ellipsis" @click.stop="expanded = true">…</span>
        <span v-if="!expanded && hasKids" class="dt-punct">&lt;/</span>
        <span v-if="!expanded && hasKids" class="dt-tag">{{ node.tag }}</span>
        <span v-if="!expanded && hasKids" class="dt-punct">&gt;</span>
        <span v-if="node.text && !hasKids" class="dt-text">{{ node.text }}</span>
        <span
          v-for="badge in node.badges || []"
          :key="badge"
          class="dt-badge"
        >{{ badge }}</span>
        <span v-if="isSelected" class="dt-selected">$0</span>
      </template>
    </div>

    <template v-if="expanded && hasKids">
      <PreviewDomTree
        v-for="(child, index) in kids"
        :key="`${id}-${index}`"
        :node="child"
        :depth="depth + 1"
        :selected-id="selectedId"
        :node-id="`${id}.${index}`"
        @select="emit('select', $event)"
      />
      <div
        v-if="!isVoid"
        class="dt-line dt-close"
        :style="{ paddingLeft: `${8 + depth * 12}px` }"
        @click="onSelect"
      >
        <span class="dt-twist spacer"></span>
        <span class="dt-punct">&lt;/</span>
        <span class="dt-tag">{{ node.tag }}</span>
        <span class="dt-punct">&gt;</span>
      </div>
    </template>
  </div>
</template>
