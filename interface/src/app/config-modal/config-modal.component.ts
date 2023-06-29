import { Component, Input } from '@angular/core'
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap'
import { CapacitorHttp } from '@capacitor/core'

@Component({
  selector: 'app-config-modal',
  templateUrl: 'config-modal.component.html',
  styles: [],
})
export class ConfigModalComponent {
  @Input() public config: any
  @Input() private baseUrl: string = ''

  constructor(public activeModal: NgbActiveModal) {}

  ngOnInit() {
    if (!this.config.zcam) {
      this.config.zcam = {}
    }
  }

  public async save() {
    const resp = await CapacitorHttp.post({
      url: `${this.baseUrl}/save`,
      data: this.config,
      headers: {
        'Content-Type': 'application/json',
      },
    })
    this.activeModal.close('save')
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
