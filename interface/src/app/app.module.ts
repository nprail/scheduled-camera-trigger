import { NgModule } from '@angular/core'
import { BrowserModule } from '@angular/platform-browser'

import { AppComponent } from './app.component'
import { NgbDatepickerModule, NgbModule } from '@ng-bootstrap/ng-bootstrap'
import { FormsModule } from '@angular/forms'
import { ConfigModalComponent } from './config-modal/config-modal.component'

@NgModule({
  declarations: [AppComponent, ConfigModalComponent],
  imports: [BrowserModule, NgbModule, FormsModule, NgbDatepickerModule],
  providers: [],
  bootstrap: [AppComponent],
})
export class AppModule {}
