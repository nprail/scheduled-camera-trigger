import React, { useEffect, useRef, useState } from 'react'
import {
  IonAccordion,
  IonAccordionGroup,
  IonButton,
  IonContent,
  IonHeader,
  IonItem,
  IonLabel,
  IonList,
  IonPage,
  IonSpinner,
  IonText,
  IonTitle,
  IonToolbar,
} from '@ionic/react'
import { Capacitor } from '@capacitor/core'
import { DateTime } from 'luxon'
import { cloneDeep } from 'lodash'
import { bleService, BleDevice, RpcEvent } from '../utils/ble'
import ConfigModal from '../components/ConfigModal'

const LOCAL_STORAGE_KEY = '_camera_scheduler_ble_device_id'
// const MAX_LOG_LINES = 10

interface DeviceInfo {
  config: {
    deviceId?: string
    deviceName?: string
    cameraType?: string
    zcam?: Record<string, unknown>
    generic?: Record<string, unknown>
    [key: string]: unknown
  }
  jobs: JobInfo[]
  recording?: boolean
}

interface JobInfo {
  name: string
  config: {
    launchTime: string
    wakeUpTime: string
    recordTime: string
    stopTime: string
  }
}

interface LogEntry {
  timestamp: string
  namespace: string
  message: string
  data: unknown
}

const round = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100

const fmt = (iso: string | undefined) => {
  if (!iso) return ''
  return DateTime.fromISO(iso).toLocaleString(DateTime.DATETIME_MED)
}

const fmtTime = (iso: string | undefined) => {
  if (!iso) return ''
  return DateTime.fromISO(iso).toLocaleString(DateTime.TIME_WITH_SECONDS)
}

const Home: React.FC = () => {
  const [info, setInfo] = useState<DeviceInfo | null>(null)
  const [logs, _setLogs] = useState<LogEntry[]>([])
  const [timestamp, setTimestamp] = useState('')
  const [timeDiff, setTimeDiff] = useState(0)

  const [loadingTest, setLoadingTest] = useState(false)
  const [loadingScan, setLoadingScan] = useState(false)
  const [loadingConnect, setLoadingConnect] = useState(false)

  const [cameraConnected, setCameraConnected] = useState(false)
  const [connectionFailed, setConnectionFailed] = useState(false)
  const [connectionError, setConnectionError] = useState('')

  const [devices, setDevices] = useState<BleDevice[]>([])
  const [lastDeviceId, setLastDeviceId] = useState(
    () => localStorage.getItem(LOCAL_STORAGE_KEY) ?? ''
  )

  const [showEditModal, setShowEditModal] = useState(false)
  const [editConfig, setEditConfig] = useState<DeviceInfo['config'] | null>(null)
  const [saveError, setSaveError] = useState('')

  const statusPollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // Keep a ref to applyStatus so the BLE callback and status poll
  // always call the latest version without re-registering.
  const applyStatusRef = useRef<(s: unknown) => void>()

  const applyStatus = (status: unknown) => {
    if (!status || typeof status !== 'object') return
    const s = status as Record<string, unknown>
    const ts = s.timestamp as string
    setTimestamp(ts)
    if (ts) {
      const diff = DateTime.fromISO(ts)
        .diff(DateTime.now(), 'seconds')
        .toObject()
      setTimeDiff(round(diff.seconds ?? 0))
    }
    setInfo((prev) => ({
      ...(prev ?? ({} as DeviceInfo)),
      config: prev?.config ?? {
        deviceId: s.deviceId as string,
        deviceName: s.deviceName as string,
        cameraType: s.cameraType as string,
      },
      jobs: (s.jobs as JobInfo[]) ?? [],
      recording: s.recording as boolean,
    }))
  }
  applyStatusRef.current = applyStatus

  const stopStatusPoll = () => {
    if (statusPollRef.current != null) {
      clearInterval(statusPollRef.current)
      statusPollRef.current = null
    }
  }

  const startStatusPoll = () => {
    stopStatusPoll()
    statusPollRef.current = setInterval(async () => {
      try {
        const status = await bleService.getStatus()
        applyStatusRef.current?.(status)
      } catch {
        // ignore transient errors; disconnect handler fires if link drops
      }
    }, 2000)
  }

  const isNative = Capacitor.isNativePlatform()
  console.log('Running on', isNative ? 'native' : 'web')

  const scan = async () => {
    setLoadingScan(true)
    setConnectionError('')
    try {
      await bleService.startScan()
      setTimeout(() => bleService.stopScan().catch(() => undefined), 10000)
    } catch (err: unknown) {
      setConnectionError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoadingScan(false)
    }
  }

  // Web-only: opens the browser's native BLE device picker then connects.
  const connectWeb = async () => {
    setLoadingConnect(true)
    setConnectionFailed(false)
    setConnectionError('')
    try {
      const device = await bleService.requestDevice()
      await bleService.connect(device.deviceId)
      localStorage.setItem(LOCAL_STORAGE_KEY, device.deviceId)
      setLastDeviceId(device.deviceId)

      const [status, config] = await Promise.all([
        bleService.getStatus(),
        bleService.getConfig(),
      ])
      applyStatusRef.current?.(status)
      setInfo({
        config: config as DeviceInfo['config'],
        jobs: ((status as Record<string, unknown>)?.jobs as JobInfo[]) ?? [],
      })
      startStatusPoll()
    } catch (err: unknown) {
      setConnectionFailed(true)
      setConnectionError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoadingConnect(false)
    }
  }

  const connect = async (deviceId?: string) => {
    const id = deviceId ?? lastDeviceId
    if (!id) {
      setConnectionError('No device selected')
      return
    }
    setLoadingConnect(true)
    setConnectionFailed(false)
    setConnectionError('')
    try {
      await bleService.connect(id)
      localStorage.setItem(LOCAL_STORAGE_KEY, id)
      setLastDeviceId(id)

      const [status, config] = await Promise.all([
        bleService.getStatus(),
        bleService.getConfig(),
      ])
      applyStatusRef.current?.(status)
      setInfo({
        config: config as DeviceInfo['config'],
        jobs: ((status as Record<string, unknown>)?.jobs as JobInfo[]) ?? [],
      })
      startStatusPoll()
    } catch (err: unknown) {
      setConnectionFailed(true)
      setConnectionError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoadingConnect(false)
    }
  }

  const disconnect = async () => {
    stopStatusPoll()
    await bleService.disconnect()
    setCameraConnected(false)
  }

  const test = async () => {
    setLoadingTest(true)
    try {
      await bleService.test()
    } catch (err) {
      console.error(err)
    } finally {
      setLoadingTest(false)
    }
  }

  const edit = () => {
    if (!info?.config) return
    setEditConfig(cloneDeep(info.config))
    setShowEditModal(true)
  }

  const handleSaved = async () => {
    setShowEditModal(false)
    setSaveError('')
    try {
      const [status, config] = await Promise.all([
        bleService.getStatus(),
        bleService.getConfig(),
      ])
      applyStatusRef.current?.(status)
      setInfo({
        config: config as DeviceInfo['config'],
        jobs: ((status as Record<string, unknown>)?.jobs as JobInfo[]) ?? [],
      })
    } catch (err: unknown) {
      setSaveError(err instanceof Error ? err.message : String(err))
    }
  }

  useEffect(() => {
    bleService.onDevices = setDevices
    bleService.onConnected = (connected) => {
      setCameraConnected(connected)
      if (!connected) stopStatusPoll()
    }
    bleService.onEvent = (evt: RpcEvent) => {
      if (evt.name === 'status') {
        applyStatusRef.current?.(evt.data)
      }
    }

    // On native, auto-scan on mount. On web, Web Bluetooth requires a user
    // gesture so we skip the auto-scan and let the user click the button.
    if (Capacitor.isNativePlatform()) {
      scan().catch(console.error)
    }

    return () => {
      bleService.onDevices = undefined
      bleService.onConnected = undefined
      bleService.onEvent = undefined
      stopStatusPoll()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <IonPage>
      <IonHeader>
        <IonToolbar>
          <IonTitle>Camera Scheduler</IonTitle>
        </IonToolbar>
      </IonHeader>

      <IonContent className="ion-padding">
        {/* ── Connection screen ── */}
        {!cameraConnected && (
          <>
            <div
              style={{
                display: 'flex',
                justifyContent: 'center',
                gap: 8,
                marginBottom: 16,
              }}
            >
              {isNative ? (
                <>
                  <IonButton
                    disabled={loadingScan || loadingConnect}
                    onClick={() => scan()}
                  >
                    {loadingScan ? (
                      <>
                        <IonSpinner name="crescent" />
                        &nbsp;Scanning…
                      </>
                    ) : (
                      'Scan for devices'
                    )}
                  </IonButton>

                  {lastDeviceId && (
                    <IonButton
                      fill="outline"
                      disabled={loadingConnect}
                      onClick={() => connect()}
                    >
                      {loadingConnect ? (
                        <>
                          <IonSpinner name="crescent" />
                          &nbsp;Connecting…
                        </>
                      ) : (
                        'Reconnect to last device'
                      )}
                    </IonButton>
                  )}
                </>
              ) : (
                <IonButton
                  disabled={loadingConnect}
                  onClick={() => connectWeb()}
                >
                  {loadingConnect ? (
                    <>
                      <IonSpinner name="crescent" />
                      &nbsp;Connecting…
                    </>
                  ) : (
                    'Connect to device'
                  )}
                </IonButton>
              )}
            </div>

            {isNative && (
              <IonList>
                {devices.map((device) => (
                  <IonItem
                    key={device.deviceId}
                    button
                    onClick={() => connect(device.deviceId)}
                  >
                    <IonLabel>
                      <h2>{device.name ?? '(unnamed device)'}</h2>
                      <p>{device.deviceId}</p>
                      {device.rssi != null && <p>RSSI: {device.rssi} dBm</p>}
                    </IonLabel>
                  </IonItem>
                ))}
              </IonList>
            )}
          </>
        )}

        {/* ── Connected action bar ── */}
        {cameraConnected && (
          <div
            style={{
              display: 'flex',
              justifyContent: 'center',
              gap: 8,
              marginBottom: 16,
            }}
          >
            <IonButton color="danger" onClick={disconnect}>
              Disconnect
            </IonButton>
            <IonButton
              color="success"
              onClick={test}
              disabled={loadingTest}
            >
              {loadingTest ? (
                <>
                  <IonSpinner name="crescent" />
                  &nbsp;Testing…
                </>
              ) : (
                'Test'
              )}
            </IonButton>
            <IonButton color="success" onClick={edit}>
              Edit
            </IonButton>
          </div>
        )}

        {/* ── Save error ── */}
        {saveError && (
          <IonText color="danger">
            <p style={{ textAlign: 'center' }}>
              Failed to refresh status after save.
              <br />
              <small>{saveError}</small>
            </p>
          </IonText>
        )}

        {/* ── Error message ── */}
        {connectionFailed && (
          <IonText color="danger">
            <p style={{ textAlign: 'center' }}>
              Failed to connect to camera scheduler.
              {connectionError && (
                <>
                  <br />
                  <small>{connectionError}</small>
                </>
              )}
            </p>
          </IonText>
        )}

        {/* ── Device info ── */}
        {info && (
          <>
            <div style={{ textAlign: 'center', marginBottom: 16 }}>
              <IonText color={cameraConnected ? 'success' : 'danger'}>
                <h2>
                  {info.config.deviceName}{' '}
                  {cameraConnected ? 'Connected' : 'Disconnected'}
                </h2>
              </IonText>
              <h4>Trigger Time: {fmtTime(timestamp)}</h4>
              <h4>Actual Time: {fmtTime(new Date().toISOString())}</h4>
              <h4>Time Difference: {timeDiff} seconds</h4>
              <h5>Type: {info.config.cameraType?.toUpperCase()}</h5>
            </div>

            <IonList>
              {info.jobs.map((job) => (
                <IonItem key={job.name}>
                  <IonLabel className="ion-text-wrap">
                    <h2>{job.name}</h2>
                    <p>
                      <strong>{fmt(job.config.launchTime)}</strong>
                      <br />
                      <small>{job.config.launchTime}</small>
                    </p>
                    <p>
                      <strong>Wake Up:</strong> {fmt(job.config.wakeUpTime)}
                    </p>
                    <p>
                      <strong>Record:</strong> {fmt(job.config.recordTime)}
                    </p>
                    <p>
                      <strong>Stop:</strong> {fmt(job.config.stopTime)}
                    </p>
                  </IonLabel>
                </IonItem>
              ))}
            </IonList>

            <IonAccordionGroup>
              <IonAccordion value="config">
                <IonItem slot="header" color="light">
                  <IonLabel>Config</IonLabel>
                </IonItem>
                <div slot="content" className="ion-padding">
                  <pre>
                    <code>{JSON.stringify(info.config, null, 2)}</code>
                  </pre>
                </div>
              </IonAccordion>

              <IonAccordion value="logs">
                <IonItem slot="header" color="light">
                  <IonLabel>Logs</IonLabel>
                </IonItem>
                <div slot="content" className="ion-padding">
                  {logs.map((log, i) => (
                    <div key={i} style={{ marginBottom: 8 }}>
                      <small>
                        {fmt(log.timestamp)} [{log.namespace}]
                      </small>{' '}
                      {log.message}
                      {log.data != null && (
                        <pre style={{ fontSize: '0.75rem' }}>
                          <code>{JSON.stringify(log.data, null, 2)}</code>
                        </pre>
                      )}
                    </div>
                  ))}
                </div>
              </IonAccordion>
            </IonAccordionGroup>
          </>
        )}

        {/* ── Config edit modal ── */}
        {editConfig && (
          <ConfigModal
            isOpen={showEditModal}
            config={editConfig}
            onDismiss={() => setShowEditModal(false)}
            onSaved={handleSaved}
          />
        )}
      </IonContent>
    </IonPage>
  )
}

export default Home
