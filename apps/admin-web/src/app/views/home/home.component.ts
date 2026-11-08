import { Component } from '@angular/core';
import { CardBodyComponent, CardComponent } from '@coreui/angular';

@Component({
  selector: 'app-home',
  imports: [CardComponent, CardBodyComponent],
  template: `
    <c-card>
      <c-card-body>
        <h1 class="h4 mb-0">Painel do marketplace</h1>
      </c-card-body>
    </c-card>
  `
})
export class HomeComponent {}
