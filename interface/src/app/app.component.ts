import { Component, OnDestroy, OnInit } from '@angular/core'
import { NgbModal } from '@ng-bootstrap/ng-bootstrap'
import { cloneDeep } from 'lodash'
import { DateTime } from 'luxon'
import { Subscription } from 'rxjs'

import { ConfigModalComponent } from './config-modal/config-modal.component'
import { BleDevice, BleService } from './utils/ble.service'

const LOCAL_STORAGE_KEY = '_camera_scheduler_ble_device_id'
const MAX_LOG_LINES = 500

@Component({
  selector: 'app-root',
  templateUrl: 'app.component.html',
  styleUrls: ['app.component.scss'],
})
export class AppComponent implements OnInit, OnDestroy {
  public info: any
  public logs: any[] = []
  public timestamp = ''
  public nowTimestamp = ''
  public timeDiff: number | undefined = 0

  public loadingTest = false
  public loadingScan = false
  public loadingConnect = false

  public cameraConnected = false
  public connectionFailed = false
  public connectionError = ''

  public devices: BleDevice[] = []
  public lastDeviceId: string =
    localStorage.getItem(LOCAL_STORAGE_KEY) ?? ''

  private statusPollHandle: any = null
  private subs: Subscription[] = []

  constructor(private modalService: NgbModal, public ble: BleService) {}

  ngOnInit() {
    this.subs.push(
      this.ble.devices$.subscribe((devices) => (this.devices = devices))
    )

    this.subs.push(
      this.ble.connected$.subscribe((connected) => {
        this.cameraConnected = connected
        if (!connected) {
          this.stopStatusPoll()
        }
      })
    )

    this.subs.push(
      this.ble.events$.subscribe((evt) => {
        if (evt.name === 'log') {
          this.logs = [evt.data, ...this.logs].slice(0, MAX_LOG_LINES)
        } else if (evt.name === 'status') {
          this.applyStatus(evt.data)
        }
      })
    )

    this.scan().catch((err) => console.error(err))
  }

  ngOnDestroy() {
    this.stopStatusPoll()
    for (const s of this.subs) s.unsubscribe()
  }

  public async scan() {
    this.loadingScan = true
    this.connectionError = ''
    try {
      await this.ble.startScan()
      // Stop the active scan after 10s to save battery.
      setTimeout(() => this.ble.stopScan().catch(() => {}), 10000)
    } catch (err: any) {
      this.connectionError = err?.message ?? String(err)
    } finally {
      this.loadingScan = false
    }
  }

  public selectDevice(device: BleDevice) {
    this.connect(device.deviceId)
  }

  public async connect(deviceId?: string) {
    const id = deviceId || this.lastDeviceId
    if (!id) {
      this.connectionError = 'No device selected'
      return
    }

    this.loadingConnect = true
    this.connectionFailed = false
    this.connectionError = ''
    try {
      await this.ble.connect(id)
      localStorage.setItem(LOCAL_STORAGE_KEY, id)
      this.lastDeviceId = id

      const [status, config, logs] = await Promise.all([
        this.ble.getStatus(),
        this.ble.getConfig(),
        this.ble.getLogs({ limit: MAX_LOG_LINES }),
      ])
      this.applyStatus(status)
      this.info = { config, jobs: status?.jobs ?? [] }
      this.logs = (logs ?? [])
        .slice()
        .sort(
          (a: any, b: any) =>
            new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
        )

      this.startStatusPoll()
    } catch (err: any) {
      this.connectionFailed = true
      this.connectionError = err?.message ?? String(err)
    } finally {
      this.loadingConnect = false
    }
  }

  public async disconnect() {
    this.stopStatusPoll()
    await this.ble.disconnect()
    this.cameraConnected = false
  }

  public async test() {
    this.loadingTest = true
    try {
      await this.ble.test()
    } catch (err) {
      console.error(err)
    } finally {
      this.loadingTest = false
    }
  }

  public async edit() {
    if (!this.info?.config) return
    const modalRef = this.modalService.open(ConfigModalComponent)
    modalRef.componentInstance.config = cloneDeep(this.info.config)
    try {
      const result = await modalRef.result
      if (result === 'save') {
        const [status, config] = await Promise.all([
          this.ble.getStatus(),
          this.ble.getConfig(),
        ])
        this.applyStatus(status)
        this.info = { config, jobs: status?.jobs ?? [] }
      }
    } catch {
      // dismissed
    }
  }

  private applyStatus(status: any) {
    if (!status) return
    this.timestamp = status.timestamp
    this.calculateDate(new Date())
    this.info = {
      ...(this.info ?? {}),
      config: this.info?.config ?? {
        deviceId: status.deviceId,
        deviceName: status.deviceName,
        cameraType: status.cameraType,
      },
      jobs: status.jobs ?? [],
      recording: status.recording,
    }
  }

  private startStatusPoll() {
    this.stopStatusPoll()
    this.statusPollHandle = setInterval(async () => {
      try {
        const status = await this.ble.getStatus()
        this.applyStatus(status)
      } catch (err) {
        // Ignore transient errors; disconnect handler will fire if link drops.
      }
    }, 2000)
  }

  private stopStatusPoll() {
    if (this.statusPollHandle) {
      clearInterval(this.statusPollHandle)
      this.statusPollHandle = null
    }
  }

  private round(num: number) {
    return Math.round((num + Number.EPSILON) * 100) / 100
  }

  private calculateDate(actualDate: Date) {
    this.nowTimestamp = actualDate.toISOString()
    if (!this.timestamp) return
    const triggerDateTime = DateTime.fromISO(this.timestamp)
    const actualDateTime = DateTime.fromJSDate(actualDate)
    const diff = triggerDateTime.diff(actualDateTime, 'seconds').toObject()
    this.timeDiff = this.round(diff.seconds ?? 0)
  }
}
