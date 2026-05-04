import { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.noahprail.camscheduler',
  appName: 'Camera Scheduler',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
  },
  plugins: {},
}

export default config
