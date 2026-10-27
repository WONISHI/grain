export interface EditorLanguage {
  id: string
  label: string
}

const byName: Record<string, EditorLanguage> = {
  dockerfile: { id: 'dockerfile', label: 'Dockerfile' },
  makefile: { id: 'plaintext', label: 'Makefile' },
}

const byExtension: Record<string, EditorLanguage> = {
  js: { id: 'javascript', label: 'JavaScript' },
  mjs: { id: 'javascript', label: 'JavaScript' },
  cjs: { id: 'javascript', label: 'JavaScript' },
  jsx: { id: 'javascript', label: 'JavaScript JSX' },
  ts: { id: 'typescript', label: 'TypeScript' },
  mts: { id: 'typescript', label: 'TypeScript' },
  cts: { id: 'typescript', label: 'TypeScript' },
  tsx: { id: 'typescript', label: 'TypeScript JSX' },
  vue: { id: 'vue', label: 'Vue' },
  json: { id: 'json', label: 'JSON' },
  jsonc: { id: 'json', label: 'JSON' },
  css: { id: 'css', label: 'CSS' },
  scss: { id: 'scss', label: 'SCSS' },
  less: { id: 'less', label: 'Less' },
  html: { id: 'html', label: 'HTML' },
  htm: { id: 'html', label: 'HTML' },
  xml: { id: 'xml', label: 'XML' },
  svg: { id: 'xml', label: 'SVG' },
  md: { id: 'markdown', label: 'Markdown' },
  markdown: { id: 'markdown', label: 'Markdown' },
  py: { id: 'python', label: 'Python' },
  java: { id: 'java', label: 'Java' },
  go: { id: 'go', label: 'Go' },
  rs: { id: 'rust', label: 'Rust' },
  kt: { id: 'kotlin', label: 'Kotlin' },
  kts: { id: 'kotlin', label: 'Kotlin' },
  swift: { id: 'swift', label: 'Swift' },
  c: { id: 'c', label: 'C' },
  h: { id: 'c', label: 'C' },
  cpp: { id: 'cpp', label: 'C++' },
  cc: { id: 'cpp', label: 'C++' },
  cxx: { id: 'cpp', label: 'C++' },
  hpp: { id: 'cpp', label: 'C++' },
  hh: { id: 'cpp', label: 'C++' },
  cs: { id: 'csharp', label: 'C#' },
  php: { id: 'php', label: 'PHP' },
  rb: { id: 'ruby', label: 'Ruby' },
  sh: { id: 'shell', label: 'Shell' },
  bash: { id: 'shell', label: 'Shell' },
  zsh: { id: 'shell', label: 'Shell' },
  yml: { id: 'yaml', label: 'YAML' },
  yaml: { id: 'yaml', label: 'YAML' },
  toml: { id: 'ini', label: 'TOML' },
  ini: { id: 'ini', label: 'INI' },
  properties: { id: 'ini', label: 'Properties' },
  sql: { id: 'sql', label: 'SQL' },
  graphql: { id: 'graphql', label: 'GraphQL' },
  gql: { id: 'graphql', label: 'GraphQL' },
  lua: { id: 'lua', label: 'Lua' },
  pl: { id: 'perl', label: 'Perl' },
  r: { id: 'r', label: 'R' },
  xmls: { id: 'xml', label: 'XML' },
}

export function languageOf(path: string): EditorLanguage {
  const base = (path.split('/').pop() ?? path).toLowerCase()
  const named = byName[base]
  if (named) return named
  const dot = base.lastIndexOf('.')
  const extension = dot >= 0 ? base.slice(dot + 1) : ''
  return byExtension[extension] ?? { id: 'plaintext', label: extension ? extension.toUpperCase() : '纯文本' }
}

export function languageId(path: string): string {
  return languageOf(path).id
}

export function languageLabel(path: string): string {
  return languageOf(path).label
}
