import { Injectable, NgZone } from '@angular/core'
import { Subject } from 'rxjs'
import {
  BleClient,
  numbersToDataView,
  ScanResult,
} from '@capacitor-community/bluetooth-le'

// Must match trigger/ble/uuids.js (pretty form).
export const SERVICE_UUID = 'b87f0001-9c4b-4e6f-9a8e-1a1b2c3d4e5f'
export const RX_UUID = 'b87f0002-9c4b-4e6f-9a8e-1a1b2c3d4e5f'
export const TX_UUID = 'b87f0003-9c4b-4e6f-9a8e-1a1b2c3d4e5f'

const HEADER_LEN = 4
const FLAG_LAST = 0x01

// Conservative chunk size for client -> device writes. Fits MTU 27 with the
// 4-byte framing header. Once requestMtu() succeeds we ramp up.
const SAFE_CHUNK_PAYLOAD = 19

export interface BleDevice {
  deviceId: string
  name?: string
  rssi?: number
}

export interface RpcEvent {
  name: string
  data: any
}

interface PendingRequest {
  resolve: (data: any) => void
  reject: (err: Error) => void
  timer: any
}

interface ReassemblyEntry {
  chunks: Map<number, Uint8Array>
  lastSeq?: number
}

@Injectable({ providedIn: 'root' })
export class BleService {
  /** Devices discovered via active scan. */
  public devices$ = new Subject<BleDevice[]>()
  /** Stream of `evt` messages pushed by the device (status, log, ...). */
  public events$ = new Subject<RpcEvent>()
  /** Connection state changes. */
  public connected$ = new Subject<boolean>()

  private devices = new Map<string, BleDevice>()
  private currentDeviceId: string | null = null
  private isInitialized = false

  private nextRpcId = 1
  private pending = new Map<number, PendingRequest>()

  private nextTxMsgId = 1
  private reasm = new Map<number, ReassemblyEntry>()
  private chunkPayload = SAFE_CHUNK_PAYLOAD

  constructor(private ngZone: NgZone) {}

  public get isConnected(): boolean {
    return this.currentDeviceId != null
  }

  public get connectedDeviceId(): string | null {
    return this.currentDeviceId
  }

  // ----- Lifecycle -----

  private async ensureInit() {
    if (this.isInitialized) return
    await BleClient.initialize({ androidNeverForLocation: true })
    this.isInitialized = true
  }

  public async startScan(): Promise<void> {
    await this.ensureInit()
    this.devices.clear()
    this.emitDevices()

    await BleClient.requestLEScan(
      { services: [SERVICE_UUID], allowDuplicates: false },
      (result: ScanResult) => {
        const dev: BleDevice = {
          deviceId: result.device.deviceId,
          name: result.device.name ?? result.localName ?? undefined,
          rssi: result.rssi,
        }
        this.devices.set(dev.deviceId, dev)
        this.emitDevices()
      }
    )
  }

  public async stopScan(): Promise<void> {
    if (!this.isInitialized) return
    try {
      await BleClient.stopLEScan()
    } catch {
      // ignore
    }
  }

  public async connect(deviceId: string): Promise<void> {
    await this.ensureInit()
    await this.stopScan()

    await BleClient.connect(deviceId, () => this.handleDisconnected(deviceId))
    this.currentDeviceId = deviceId

    // The plugin negotiates MTU automatically but doesn't expose the result,
    // so we stick to the conservative chunk size that fits MTU 27.
    this.chunkPayload = SAFE_CHUNK_PAYLOAD

    await BleClient.startNotifications(
      deviceId,
      SERVICE_UUID,
      TX_UUID,
      (value) => this.handleNotification(value)
    )

    this.ngZone.run(() => this.connected$.next(true))
  }

  public async disconnect(): Promise<void> {
    if (!this.currentDeviceId) return
    const id = this.currentDeviceId
    try {
      await BleClient.stopNotifications(id, SERVICE_UUID, TX_UUID)
    } catch {}
    try {
      await BleClient.disconnect(id)
    } catch {}
    this.handleDisconnected(id)
  }

  private handleDisconnected(_deviceId: string) {
    this.currentDeviceId = null
    this.reasm.clear()
    for (const p of this.pending.values()) {
      clearTimeout(p.timer)
      p.reject(new Error('Disconnected'))
    }
    this.pending.clear()
    this.ngZone.run(() => this.connected$.next(false))
  }

  // ----- High-level API -----

  public getStatus = () => this.request('getStatus')
  public getConfig = () => this.request('getConfig')
  public setConfig = (config: any) => this.request('setConfig', config)
  public getLogs = (params?: { since?: string; limit?: number }) =>
    this.request('getLogs', params)
  public test = () => this.request('test')

  // ----- RPC plumbing -----

  private request(method: string, params?: any, timeoutMs = 10000): Promise<any> {
    if (!this.currentDeviceId) {
      return Promise.reject(new Error('Not connected'))
    }
    const id = this.nextRpcId++
    const payload = JSON.stringify({ type: 'req', id, method, params })

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`RPC '${method}' timed out`))
      }, timeoutMs)

      this.pending.set(id, { resolve, reject, timer })

      this.sendFramed(payload).catch((err) => {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(err)
      })
    })
  }

  private async sendFramed(payload: string): Promise<void> {
    if (!this.currentDeviceId) throw new Error('Not connected')
    const deviceId = this.currentDeviceId

    const msgId = this.allocTxId()
    const bytes = new TextEncoder().encode(payload)
    const chunkSize = this.chunkPayload

    const total = bytes.length === 0 ? 1 : Math.ceil(bytes.length / chunkSize)
    if (total > 256) {
      throw new Error('Payload too large for current chunk size')
    }

    for (let i = 0; i < total; i++) {
      const off = i * chunkSize
      const end = Math.min(off + chunkSize, bytes.length)
      const chunk = bytes.subarray(off, end)
      const isLast = i === total - 1

      const frame = new Uint8Array(HEADER_LEN + chunk.length)
      frame[0] = (msgId >> 8) & 0xff
      frame[1] = msgId & 0xff
      frame[2] = i & 0xff
      frame[3] = isLast ? FLAG_LAST : 0
      frame.set(chunk, HEADER_LEN)

      await BleClient.write(
        deviceId,
        SERVICE_UUID,
        RX_UUID,
        numbersToDataView(Array.from(frame))
      )
    }
  }

  private allocTxId(): number {
    const id = this.nextTxMsgId
    this.nextTxMsgId = (this.nextTxMsgId + 1) & 0xffff
    if (this.nextTxMsgId === 0) this.nextTxMsgId = 1
    return id
  }

  private handleNotification(value: DataView) {
    if (!value || value.byteLength < HEADER_LEN) return

    const msgId = (value.getUint8(0) << 8) | value.getUint8(1)
    const seq = value.getUint8(2)
    const flags = value.getUint8(3)
    const payload = new Uint8Array(
      value.buffer,
      value.byteOffset + HEADER_LEN,
      value.byteLength - HEADER_LEN
    )

    let entry = this.reasm.get(msgId)
    if (!entry) {
      entry = { chunks: new Map() }
      this.reasm.set(msgId, entry)
    }
    // Copy because the underlying buffer may be reused by the plugin.
    entry.chunks.set(seq, new Uint8Array(payload))
    if (flags & FLAG_LAST) entry.lastSeq = seq

    if (entry.lastSeq === undefined) return
    for (let i = 0; i <= entry.lastSeq; i++) {
      if (!entry.chunks.has(i)) return
    }

    let totalLen = 0
    for (let i = 0; i <= entry.lastSeq; i++) {
      totalLen += entry.chunks.get(i)!.length
    }
    const assembled = new Uint8Array(totalLen)
    let offset = 0
    for (let i = 0; i <= entry.lastSeq; i++) {
      const c = entry.chunks.get(i)!
      assembled.set(c, offset)
      offset += c.length
    }
    this.reasm.delete(msgId)

    let msg: any
    try {
      msg = JSON.parse(new TextDecoder().decode(assembled))
    } catch (err) {
      console.error('BLE: bad JSON payload', err)
      return
    }

    this.handleMessage(msg)
  }

  private handleMessage(msg: any) {
    if (msg?.type === 'res' && typeof msg.id === 'number') {
      const pending = this.pending.get(msg.id)
      if (!pending) return
      this.pending.delete(msg.id)
      clearTimeout(pending.timer)
      if (msg.ok) pending.resolve(msg.data)
      else pending.reject(new Error(msg.error || 'RPC error'))
      return
    }

    if (msg?.type === 'evt' && typeof msg.name === 'string') {
      this.ngZone.run(() => this.events$.next({ name: msg.name, data: msg.data }))
      return
    }
  }

  private emitDevices() {
    const list = Array.from(this.devices.values())
    this.ngZone.run(() => this.devices$.next(list))
  }
}
