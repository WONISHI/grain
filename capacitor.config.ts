import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'app.grain.dev',
  appName: 'Grain',
  webDir: 'dist',
  backgroundColor: '#0b0d10',
  server: {
    androidScheme: 'https',
    // 之后的 localhost 预览会走明文 HTTP。正式包不要开。
    cleartext: true,
  },
  android: {
    allowMixedContent: true,
    webContentsDebuggingEnabled: true,
    backgroundColor: '#0b0d10',
  },
}

export default config
