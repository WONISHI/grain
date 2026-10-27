<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  SETTING_FIELDS,
  SETTINGS_DEFAULTS,
  resetAllSettings,
  resetSetting,
  settings,
  type GrainSettings,
  type SettingField,
} from '../settings'
import VscodeIcon from './VscodeIcon.vue'

const query = ref('')

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return SETTING_FIELDS
  return SETTING_FIELDS.filter((field) => {
    const hay = `${field.category} ${field.title} ${field.description} ${field.id}`.toLowerCase()
    return hay.includes(q)
  })
})

const groups = computed(() => {
  const map = new Map<string, SettingField[]>()
  for (const field of filtered.value) {
    const list = map.get(field.category) ?? []
    list.push(field)
    map.set(field.category, list)
  }
  return [...map.entries()]
})

function isModified(id: keyof GrainSettings): boolean {
  return settings[id] !== SETTINGS_DEFAULTS[id]
}

function onNumberInput(field: SettingField, event: Event) {
  const input = event.target as HTMLInputElement
  const raw = Number(input.value)
  if (!Number.isFinite(raw)) return
  const min = field.min ?? Number.NEGATIVE_INFINITY
  const max = field.max ?? Number.POSITIVE_INFINITY
  ;(settings as Record<string, unknown>)[field.id] = Math.min(max, Math.max(min, raw))
}

function onStringInput(field: SettingField, event: Event) {
  const input = event.target as HTMLInputElement
  ;(settings as Record<string, unknown>)[field.id] = input.value
}

function onEnumChange(field: SettingField, event: Event) {
  const select = event.target as HTMLSelectElement
  ;(settings as Record<string, unknown>)[field.id] = select.value
}

function onBoolToggle(field: SettingField) {
  const current = settings[field.id]
  ;(settings as Record<string, unknown>)[field.id] = !current
}
</script>

<template>
  <div class="settings-editor">
    <header class="settings-toolbar">
      <div class="settings-search">
        <input
          v-model="query"
          type="search"
          placeholder="搜索设置"
          aria-label="搜索设置"
          autocomplete="off"
          spellcheck="false"
        />
      </div>
      <button type="button" class="settings-reset-all" title="全部恢复默认" @click="resetAllSettings">
        恢复默认
      </button>
    </header>

    <div class="settings-body">
      <p v-if="!groups.length" class="settings-empty">没有匹配的设置</p>
      <section v-for="[category, fields] in groups" :key="category" class="settings-group">
        <h2 class="settings-group-title">{{ category }}</h2>
        <article
          v-for="field in fields"
          :key="field.id"
          class="settings-item"
          :class="{ modified: isModified(field.id) }"
        >
          <div class="settings-item-head">
            <div class="settings-item-titles">
              <h3>{{ field.title }}</h3>
              <code class="settings-id">{{ field.id }}</code>
            </div>
            <button
              v-if="isModified(field.id)"
              type="button"
              class="settings-item-reset"
              title="重置为默认值"
              aria-label="重置为默认值"
              @click="resetSetting(field.id)"
            >
              <VscodeIcon name="discard" :size="14" />
            </button>
          </div>
          <p class="settings-desc">{{ field.description }}</p>
          <div class="settings-control">
            <input
              v-if="field.type === 'number'"
              type="number"
              :min="field.min"
              :max="field.max"
              :step="field.step ?? 1"
              :value="settings[field.id]"
              @change="onNumberInput(field, $event)"
            />
            <input
              v-else-if="field.type === 'string'"
              type="text"
              spellcheck="false"
              :value="String(settings[field.id])"
              @change="onStringInput(field, $event)"
            />
            <select
              v-else-if="field.type === 'enum'"
              :value="String(settings[field.id])"
              @change="onEnumChange(field, $event)"
            >
              <option v-for="opt in field.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
            <button
              v-else
              type="button"
              class="settings-check"
              role="checkbox"
              :aria-checked="Boolean(settings[field.id])"
              @click="onBoolToggle(field)"
            >
              <span class="settings-check-box" :class="{ on: Boolean(settings[field.id]) }"></span>
              <span>{{ settings[field.id] ? '已启用' : '已禁用' }}</span>
            </button>
          </div>
        </article>
      </section>
    </div>
  </div>
</template>
