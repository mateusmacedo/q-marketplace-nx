import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  provideCatalogInventoryApi,
  type SkuResponse,
} from '@marketplace/catalog-inventory-admin-data-access';

import { SkuPanelComponent } from './sku-panel.component';

@Component({
  imports: [SkuPanelComponent],
  template: '<app-sku-panel [sku]="sku" currency="BRL" />',
})
class PanelHost {
  readonly sku: SkuResponse = { sellerSku: 'CAM-P-AZ' };
}

describe('SkuPanelComponent', () => {
  it('põe cabeçalho e corpo no flex do painel, para o corpo rolar até o estoque', async () => {
    TestBed.configureTestingModule({
      imports: [PanelHost],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()],
    });
    const fixture = TestBed.createComponent(PanelHost);
    await fixture.whenStable();

    const offcanvas = (fixture.nativeElement as HTMLElement).querySelector(
      '.offcanvas',
    ) as HTMLElement;
    const wrapper = offcanvas.querySelector(
      ':scope > div:not(.cdk-focus-trap-anchor)',
    ) as HTMLElement;

    expect(wrapper.contains(offcanvas.querySelector('c-offcanvas-body'))).toBe(true);
    expect(getComputedStyle(wrapper).display).toBe('contents');
  });
});
