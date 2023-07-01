import { Component, OnInit } from '@angular/core'
import { CapacitorHttp } from '@capacitor/core'
import { NgbModal } from '@ng-bootstrap/ng-bootstrap'
import { cloneDeep } from 'lodash'
import { ZeroConfService } from 'capacitor-zeroconf'

import { ConfigModalComponent } from './config-modal/config-modal.component'
import { BonjourService } from './utils/bonjour.service'

const LOCAL_STORAGE_KEY = '_camera_scheduler_ip_address'

@Component({
  selector: 'app-root',
  templateUrl: 'app.component.html',
  styleUrls: ['app.component.scss'],
})
export class AppComponent implements OnInit {
  public info: any
  public logs: any[] = []
  public timestamp = ''

  public killWifiConfirmed = false
  public loadingKillWifi = false
  public killWifiResponse = ''
  public loadingTest = false

  private logFetchInterval: number | undefined
  public requestFailCount = 0
  public cameraConnected = false

  public connectionFailed = false

  public ipAddress: string = localStorage.getItem(LOCAL_STORAGE_KEY) ?? ''

  public get baseUrl() {
    return `http://${this.ipAddress}:3000`
  }

  public services: ZeroConfService[] = []

  constructor(private modalService: NgbModal, public bonjour: BonjourService) {}

  public select(service: any) {
    this.ipAddress = service.ipv4Addresses[0]
    this.connect()
  }

  ngOnInit() {
    this.bonjour.start()

    this.bonjour.services$.subscribe((services) => {
      this.services = services
    })
  }

  public async getInfo() {
    try {
      if (this.logFetchInterval) {
        clearInterval(this.logFetchInterval)
        this.logFetchInterval = undefined
      }

      const response = await CapacitorHttp.get({
        url: this.baseUrl,
        readTimeout: 3000,
        connectTimeout: 3000,
      })

      this.connectionFailed = false
      this.timestamp = response.data.timestamp
      this.info = response.data
      this.cameraConnected = true
      this.killWifiResponse = ''

      this.fetchLogs()

      this.logFetchInterval = setInterval(() => {
        this.fetchLogs()
      }, 1000) as any
    } catch (err) {
      this.connectionFailed = true
    }
  }

  public async fetchLogs() {
    try {
      const resp = await CapacitorHttp.get({
        url: `${this.baseUrl}/logs`,
        readTimeout: 1000,
        connectTimeout: 1000,
      })

      this.timestamp = resp.data.timestamp
      this.cameraConnected = true
      this.logs = resp.data.logs.sort(
        (a: any, b: any) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
      )
      this.requestFailCount = 0
    } catch (err) {
      this.requestFailCount++

      if (this.requestFailCount >= 5) {
        clearInterval(this.logFetchInterval)
        this.logFetchInterval = undefined
        this.cameraConnected = false
      }
    }
  }

  public connect() {
    localStorage.setItem(LOCAL_STORAGE_KEY, this.ipAddress)

    this.getInfo()
  }

  public disconnect() {
    this.cameraConnected = false
    clearInterval(this.logFetchInterval)
    this.logFetchInterval = undefined
  }

  public async test() {
    this.loadingTest = true
    try {
      const resp = await CapacitorHttp.post({
        url: `${this.baseUrl}/test`,
        readTimeout: 3000,
        connectTimeout: 3000,
      })
    } catch (err) {
      console.error(err)
    } finally {
      this.loadingTest = false
    }
  }

  public async edit() {
    const modalRef = this.modalService.open(ConfigModalComponent)
    modalRef.componentInstance.config = cloneDeep(this.info.config)
    modalRef.componentInstance.baseUrl = this.baseUrl
    modalRef.result
      .then((resp) => {
        if (resp === 'save') this.getInfo()
      })
      .catch(() => {
        // ignore
      })
  }

  public async killWifi() {
    if (!this.killWifiConfirmed) {
      this.killWifiConfirmed = true
      return
    }

    this.loadingKillWifi = true
    this.killWifiConfirmed = false

    try {
      const resp = await CapacitorHttp.post({
        url: `${this.baseUrl}/kill-wifi`,
        readTimeout: 3000,
        connectTimeout: 3000,
      })

      this.loadingKillWifi = false
      this.killWifiResponse =
        'Response was received. The command probably failed?'
    } catch (err: any) {
      if (err?.message ?? err === 'timeout') {
        this.killWifiResponse =
          'Response timed out. WiFi was probably successfully killed'
      } else {
        this.killWifiResponse =
          'Non-timeout error ocurred. The command probably failed?'
      }

      this.loadingKillWifi = false
    }
  }
}
