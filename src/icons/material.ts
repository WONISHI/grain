import { generateManifest } from 'material-icon-theme'

const manifest = generateManifest({ activeIconPack: 'vue' })
const base = `${import.meta.env.BASE_URL}material-icons/`

function urlFor(iconId: string | undefined): string {
  const iconPath = iconId ? manifest.iconDefinitions?.[iconId]?.iconPath : undefined
  const file = iconPath?.split('/').pop()
  return `${base}${file?.endsWith('.svg') ? file : 'file.svg'}`
}

/** Longest extension wins, matching the Material Icon Theme manifest. */
function extensionIcon(name: string): string | undefined {
  const extensions = manifest.fileExtensions
  if (!extensions) return undefined
  let rest = name
  while (rest.includes('.')) {
    rest = rest.slice(rest.indexOf('.') + 1)
    const icon = extensions[rest]
    if (icon) return icon
  }
  return undefined
}

export function fileIconUrl(path: string): string {
  const name = path.split('/').pop()?.toLowerCase() ?? ''
  const iconId = manifest.fileNames?.[name] ?? extensionIcon(name) ?? manifest.file
  return urlFor(iconId)
}

export const folderIcon = urlFor(manifest.folder)
export const workspaceFolderIcon = urlFor(manifest.folderExpanded ?? manifest.folder)
