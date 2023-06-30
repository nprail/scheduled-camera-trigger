import { Injectable, NgZone } from '@angular/core'
import { ZeroConf, ZeroConfService } from 'capacitor-zeroconf'
import { cloneDeep } from 'lodash'
import { Subject } from 'rxjs'

@Injectable({
  providedIn: 'root',
})
export class BonjourService {
  private _services: ZeroConfService[] = []

  public services$: Subject<ZeroConfService[]> = new Subject()

  constructor(private ngZone: NgZone) {}

  public async start() {
    try {
      await ZeroConf.watch(
        {
          type: '_http._tcp.',
          domain: 'local.',
        },
        (result) => {
          if (!result) return

          if (
            result.service.txtRecord['_scheduled_camera_trigger'] !==
            'com.noahprail.camscheduler'
          ) {
            return
          }

          if (result.action === 'resolved') {
            this.addService(result.service)
          }

          if (result.action === 'removed') {
            this.removeService(result.service)
          }
        }
      )
    } catch (err) {
      console.error(err)
    }
  }

  private findServiceIndex(service: ZeroConfService) {
    return this._services.findIndex((s) => s.name === service.name)
  }

  private addService(_service: ZeroConfService) {
    const service = cloneDeep(_service)

    // remove ip addresses that start with 10.98.32 because that is the ZCam's network
    service.ipv4Addresses = service.ipv4Addresses.filter(
      (ip) => !ip.startsWith('10.98.32')
    )

    const existingIndex = this.findServiceIndex(service)

    // remove the existing item
    if (existingIndex !== -1) {
      this._services.splice(existingIndex, 1)
    }

    // add the updated one
    this._services.push(service)

    this.ngZone.run(() => this.services$.next(this._services))
  }

  private removeService(service: ZeroConfService) {
    const index = this.findServiceIndex(service)

    if (index !== -1) return

    this._services.splice(index, 1)

    this.ngZone.run(() => this.services$.next(this._services))
  }
}
