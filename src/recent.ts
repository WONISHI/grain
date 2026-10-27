export type RecentKind = 'file' | 'folder'

export interface RecentItem {
  kind: RecentKind
  /** Display name */
  name: string
  /** Workspace-relative path for files; folder id/name for folders */
  path: string
  at: number
}

/** v2：只记录「打开文件夹 / 打开文件」入口，不再把编辑器内点开的文件算进去 */
const STORAGE_KEY = 'grain.recentItems.v2'
const MAX_ITEMS = 8

function keyOf(item: Pick<RecentItem, 'kind' | 'path' | 'name'>): string {
  return `${item.kind}:${item.path || item.name}`
}

export function readRecentItems(): RecentItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item): item is RecentItem => {
        if (!item || typeof item !== 'object') return false
        const row = item as Partial<RecentItem>
        return (row.kind === 'file' || row.kind === 'folder')
          && typeof row.name === 'string'
          && row.name.trim().length > 0
          && typeof row.path === 'string'
          && typeof row.at === 'number'
      })
      .slice(0, MAX_ITEMS)
  } catch {
    return []
  }
}

export function writeRecentItems(items: RecentItem[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items.slice(0, MAX_ITEMS)))
  } catch {
    /* ignore */
  }
}

export function rememberRecentItem(input: Omit<RecentItem, 'at'> & { at?: number }): RecentItem[] {
  const next: RecentItem = {
    kind: input.kind,
    name: input.name.trim() || (input.kind === 'folder' ? 'workspace' : 'file'),
    path: input.path,
    at: input.at ?? Date.now(),
  }
  const rest = readRecentItems().filter((item) => keyOf(item) !== keyOf(next))
  const items = [next, ...rest].slice(0, MAX_ITEMS)
  writeRecentItems(items)
  return items
}
