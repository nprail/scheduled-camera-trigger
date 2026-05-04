import { Component, Input, OnInit } from '@angular/core'
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap'

import { BleService } from '../utils/ble.service'

@Component({
  selector: 'app-config-modal',
  templateUrl: 'config-modal.component.html',
  styles: [],
})
export class ConfigModalComponent implements OnInit {
  @Input() public config: any

  public saving = false
  public error = ''

  constructor(public activeModal: NgbActiveModal, private ble: BleService) {}

  ngOnInit() {
    if (!this.config.zcam) {
      this.config.zcam = {}
    }
    if (!this.config.generic) {
      this.config.generic = {}
    }
  }

  public async save() {
    this.saving = true
    this.error = ''
    try {
      await this.ble.setConfig(this.config)
      this.activeModal.close('save')
    } catch (err: any) {
      this.error = err?.message ?? String(err)
    } finally {
      this.saving = false
    }
  }

  public addAttempt() {
    const date = new Date()
    date.setMilliseconds(0)
    date.setSeconds(0)

    this.config.attempts.push({
      name: `Attempt ${this.config.attempts.length + 1}`,
      time: date.toISOString(),
    })
  }

  public removeAttempt(name: string) {
    this.config.attempts = this.config.attempts.filter(
      (a: any) => a.name !== name
    )
  }
}
