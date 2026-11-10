import '@angular/compiler';
import '@analogjs/vitest-angular/setup-snapshots';
import { setupTestBed } from '@analogjs/vitest-angular/setup-testbed';

// WHY: o jsdom não tem a propriedade `inert`, que o alerta e o toast do CoreUI ligam no host (NG0303 no console).
if (!('inert' in HTMLElement.prototype)) {
  Object.defineProperty(HTMLElement.prototype, 'inert', {
    configurable: true,
    get(this: HTMLElement) {
      return this.hasAttribute('inert');
    },
    set(this: HTMLElement, value: boolean) {
      this.toggleAttribute('inert', !!value);
    },
  });
}

// WHY: o jsdom não tem `IntersectionObserver`, que o tooltip do CoreUI cria ao entrar na tela (menu de ações).
if (!('IntersectionObserver' in globalThis)) {
  class InertIntersectionObserver {
    observe(): void {
      return undefined;
    }
    unobserve(): void {
      return undefined;
    }
    disconnect(): void {
      return undefined;
    }
    takeRecords(): [] {
      return [];
    }
  }
  Object.defineProperty(globalThis, 'IntersectionObserver', {
    configurable: true,
    value: InertIntersectionObserver,
  });
}

setupTestBed();
