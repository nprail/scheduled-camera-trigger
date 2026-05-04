import { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.noahprail.camscheduler',
  appName: 'Camera Scheduler',
  webDir: 'dist/interface',
  server: {
    androidScheme: 'https',
  },
  plugins: {},
}

export default config
