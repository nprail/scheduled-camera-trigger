import React, { useEffect, useState } from 'react'
import {
  IonAccordion,
  IonAccordionGroup,
  IonButton,
  IonButtons,
  IonContent,
  IonFooter,
  IonHeader,
  IonInput,
  IonItem,
  IonLabel,
  IonModal,
  IonSelect,
  IonSelectOption,
  IonSpinner,
  IonText,
  IonTitle,
  IonToolbar,
} from '@ionic/react'
import { DateTime } from 'luxon'
import { bleService } from '../utils/ble'

interface Config {
  cameraType?: string
  zcam?: { cameraIp?: string }
  generic?: { releaseGpioPort?: number; focusGpioPort?: number }
  wakeUpTimeout?: string
  startBefore?: string
  endAfter?: string
  attempts?: Attempt[]
  [key: string]: unknown
}

interface Attempt {
  name: string
  time: string
}

interface Props {
  isOpen: boolean
  config: Config
  onDismiss: () => void
  onSaved: () => void
}

const fmt = (iso: string | undefined) => {
  if (!iso) return ''
  return DateTime.fromISO(iso).toLocaleString(DateTime.DATETIME_MED)
}

const toLocalInput = (iso: string | undefined): string => {
  if (!iso) return ''
  return DateTime.fromISO(iso).toLocal().toFormat("yyyy-MM-dd'T'HH:mm")
}

const fromLocalInput = (localVal: string): string => {
  if (!localVal) return ''
  return DateTime.fromISO(localVal, { zone: 'local' }).toUTC().toISO() ?? ''
}

const fmtUtcReadable = (iso: string | undefined): string => {
  if (!iso) return '—'
  return DateTime.fromISO(iso).toUTC().toLocaleString(DateTime.DATETIME_MED_WITH_SECONDS) + ' UTC'
}

const ConfigModal: React.FC<Props> = ({
  isOpen,
  config: initialConfig,
  onDismiss,
  onSaved,
}) => {
  const [config, setConfig] = useState<Config>(initialConfig)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  // Reset state when modal opens with new config
  useEffect(() => {
    if (isOpen) {
      const cfg = { ...initialConfig }
      if (!cfg.zcam) cfg.zcam = {}
      if (!cfg.generic) cfg.generic = {}
      if (cfg.attempts) {
        cfg.attempts = (cfg.attempts as unknown[]).map((a, i) =>
          typeof a === 'string'
            ? { name: `Attempt ${i + 1}`, time: a }
            : (a as Attempt)
        )
      }
      setConfig(cfg)
      setSaving(false)
      setError('')
    }
  }, [isOpen, initialConfig])

  const set = (key: string, value: unknown) =>
    setConfig((prev) => ({ ...prev, [key]: value }))

  const setNested = (parent: 'zcam' | 'generic', key: string, value: unknown) =>
    setConfig((prev) => ({
      ...prev,
      [parent]: { ...(prev[parent] ?? {}), [key]: value },
    }))

  const updateAttempt = (index: number, key: string, value: unknown) =>
    setConfig((prev) => {
      const attempts = [...(prev.attempts ?? [])]
      attempts[index] = { ...attempts[index], [key]: value }
      return { ...prev, attempts }
    })

  const addAttempt = () => {
    const date = new Date()
    date.setMilliseconds(0)
    date.setSeconds(0)
    setConfig((prev) => ({
      ...prev,
      attempts: [
        ...(prev.attempts ?? []),
        {
          name: `Attempt ${(prev.attempts?.length ?? 0) + 1}`,
          time: date.toISOString(),
        },
      ],
    }))
  }

  const removeAttempt = (name: string) =>
    setConfig((prev) => ({
      ...prev,
      attempts: (prev.attempts ?? []).filter((a) => a.name !== name),
    }))

  const save = async () => {
    setSaving(true)
    setError('')
    try {
      const payload = {
        ...config,
        attempts: (config.attempts ?? []).map((a) => a.time),
      }
      await bleService.setConfig(payload)
      onSaved()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <IonModal isOpen={isOpen} onDidDismiss={onDismiss}>
      <IonHeader>
        <IonToolbar>
          <IonTitle>Edit Schedule Config</IonTitle>
          <IonButtons slot="end">
            <IonButton onClick={onDismiss}>Close</IonButton>
          </IonButtons>
        </IonToolbar>
      </IonHeader>

      <IonContent>
        <IonItem>
          <IonSelect
            label="Camera"
            value={config.cameraType}
            onIonChange={(e) => set('cameraType', e.detail.value)}
          >
            <IonSelectOption value="generic">Generic</IonSelectOption>
            <IonSelectOption value="zcam">ZCam</IonSelectOption>
          </IonSelect>
        </IonItem>

        {config.cameraType === 'zcam' && config.zcam && (
          <IonItem>
            <IonInput
              label="ZCam IP Address"
              labelPlacement="stacked"
              value={config.zcam.cameraIp}
              onIonInput={(e) =>
                setNested('zcam', 'cameraIp', e.detail.value)
              }
            />
          </IonItem>
        )}

        {config.cameraType === 'generic' && config.generic && (
          <>
            <IonItem>
              <IonInput
                label="Release GPIO Pin"
                labelPlacement="stacked"
                type="number"
                value={config.generic.releaseGpioPort}
                onIonInput={(e) =>
                  setNested('generic', 'releaseGpioPort', e.detail.value)
                }
              />
            </IonItem>
            <IonItem>
              <IonInput
                label="Focus GPIO Pin (optional)"
                labelPlacement="stacked"
                type="number"
                value={config.generic.focusGpioPort}
                onIonInput={(e) =>
                  setNested('generic', 'focusGpioPort', e.detail.value)
                }
              />
            </IonItem>
          </>
        )}

        <IonItem>
          <IonInput
            label="Wake Up Timeout"
            labelPlacement="stacked"
            value={config.wakeUpTimeout}
            onIonInput={(e) => set('wakeUpTimeout', e.detail.value)}
          />
        </IonItem>

        <IonItem>
          <IonInput
            label="Record Pre-Buffer Time"
            labelPlacement="stacked"
            value={config.startBefore}
            onIonInput={(e) => set('startBefore', e.detail.value)}
          />
        </IonItem>

        <IonItem>
          <IonInput
            label="Record Post-Buffer Time"
            labelPlacement="stacked"
            value={config.endAfter}
            onIonInput={(e) => set('endAfter', e.detail.value)}
          />
        </IonItem>

        <div className="ion-padding">
          <IonButton expand="block" onClick={addAttempt}>
            Add Attempt
          </IonButton>
        </div>

        {error && (
          <IonText color="danger">
            <p className="ion-padding">{error}</p>
          </IonText>
        )}

        <IonAccordionGroup>
          {(config.attempts ?? []).map((attempt, index) => (
            <IonAccordion key={index} value={String(index)}>
              <IonItem slot="header" color="light">
                <IonLabel>
                  <h3>{attempt.name}</h3>
                  {attempt.time && (
                    <p style={{ fontSize: '0.85em', opacity: 0.8 }}>
                      {fmt(attempt.time)}
                    </p>
                  )}
                </IonLabel>
              </IonItem>
              <div slot="content" className="ion-padding">
                <IonItem>
                  <IonInput
                    label="Name"
                    labelPlacement="stacked"
                    value={attempt.name}
                    onIonInput={(e) =>
                      updateAttempt(index, 'name', e.detail.value)
                    }
                  />
                </IonItem>
                <IonItem>
                  <IonInput
                    label="Date & Time (local)"
                    labelPlacement="stacked"
                    type="datetime-local"
                    value={toLocalInput(attempt.time)}
                    onIonInput={(e) =>
                      updateAttempt(index, 'time', fromLocalInput(String(e.detail.value ?? '')))
                    }
                  />
                </IonItem>

                {/* Timezone + UTC info box */}
                <div
                  style={{
                    margin: '12px 0',
                    borderRadius: '8px',
                    overflow: 'hidden',
                    border: '2px solid var(--ion-color-primary)',
                  }}
                >
                  <div
                    style={{
                      background: 'var(--ion-color-primary)',
                      color: 'var(--ion-color-primary-contrast)',
                      padding: '6px 12px',
                      fontWeight: 700,
                      fontSize: '0.78em',
                      letterSpacing: '0.05em',
                      textTransform: 'uppercase',
                    }}
                  >
                    Time Zone &amp; UTC
                  </div>
                  <div style={{ padding: '10px 12px', background: 'var(--ion-color-primary-tint, #eef)' }}>
                    <div style={{ marginBottom: '6px' }}>
                      <span
                        style={{
                          fontWeight: 700,
                          fontSize: '0.85em',
                          color: 'var(--ion-color-primary-shade)',
                          textTransform: 'uppercase',
                          letterSpacing: '0.04em',
                        }}
                      >
                        Local Timezone:
                      </span>{' '}
                      <span style={{ fontWeight: 600 }}>
                        {DateTime.local().zoneName}
                      </span>{' '}
                      <span
                        style={{
                          background: 'var(--ion-color-primary)',
                          color: 'var(--ion-color-primary-contrast)',
                          borderRadius: '4px',
                          padding: '1px 6px',
                          fontSize: '0.85em',
                          fontWeight: 700,
                        }}
                      >
                        UTC{DateTime.local().toFormat('ZZ')}
                      </span>
                    </div>
                    <div>
                      <span
                        style={{
                          fontWeight: 700,
                          fontSize: '0.85em',
                          color: 'var(--ion-color-primary-shade)',
                          textTransform: 'uppercase',
                          letterSpacing: '0.04em',
                        }}
                      >
                        Exact UTC Timestamp:
                      </span>
                      <div
                        style={{
                          fontFamily: 'monospace',
                          fontSize: '0.95em',
                          fontWeight: 700,
                          marginTop: '3px',
                          wordBreak: 'break-all',
                          color: 'var(--ion-color-dark)',
                        }}
                      >
                        {attempt.time || '—'}
                      </div>
                      <div
                        style={{
                          fontSize: '0.82em',
                          color: 'var(--ion-color-medium)',
                          marginTop: '2px',
                        }}
                      >
                        {fmtUtcReadable(attempt.time)}
                      </div>
                    </div>
                  </div>
                </div>

                <div className="ion-padding-top">
                  <IonButton
                    color="danger"
                    expand="block"
                    onClick={() => removeAttempt(attempt.name)}
                  >
                    Remove
                  </IonButton>
                </div>
              </div>
            </IonAccordion>
          ))}
        </IonAccordionGroup>
      </IonContent>

      <IonFooter>
        <IonToolbar>
          <IonButton
            expand="full"
            color="success"
            onClick={save}
            disabled={saving}
          >
            {saving ? (
              <>
                <IonSpinner name="crescent" />
                &nbsp;Saving…
              </>
            ) : (
              'Save'
            )}
          </IonButton>
        </IonToolbar>
      </IonFooter>
    </IonModal>
  )
}

export default ConfigModal
