import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ToastHostComponent } from './toast-host.component';
import { ToastService } from './toast.service';

describe('ToastService', () => {
  it('acrescenta o toast com o título padrão do tipo e fecha pelo id', () => {
    const service = TestBed.inject(ToastService);

    const first = service.success('Produto enviado para moderação');
    service.warning('Você não tem acesso a esta área', 'Acesso');

    expect(service.toasts().map(toast => [toast.kind, toast.title, toast.message])).toEqual([
      ['success', 'Pronto', 'Produto enviado para moderação'],
      ['warning', 'Acesso', 'Você não tem acesso a esta área']
    ]);

    service.dismiss(first);
    expect(service.toasts().map(toast => toast.message)).toEqual(['Você não tem acesso a esta área']);
  });
});

describe('ToastHostComponent', () => {
  let fixture: ComponentFixture<ToastHostComponent>;
  let service: ToastService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ToastHostComponent] }).compileComponents();
    service = TestBed.inject(ToastService);
    fixture = TestBed.createComponent(ToastHostComponent);
    await fixture.whenStable();
  });

  it('mostra o toast no canto superior direito com marcador, título, hora, mensagem e barra de tempo', async () => {
    service.success('Produto enviado para moderação', 'Catálogo');
    await fixture.whenStable();

    const element: HTMLElement = fixture.nativeElement;
    const toaster = element.querySelector('c-toaster') as HTMLElement;
    expect(toaster.classList).toContain('top-0');
    expect(toaster.classList).toContain('end-0');
    expect(toaster.classList).toContain('position-fixed');
    expect(element.querySelector('rect')?.getAttribute('fill')).toBe('var(--cui-success)');
    expect(element.querySelector('c-toast-header strong')?.textContent).toBe('Catálogo');
    expect(element.querySelector('c-toast-header small')?.textContent).toMatch(/^\d{2}:\d{2}$/);
    expect(element.querySelector('c-toast-body p')?.textContent).toBe('Produto enviado para moderação');
    expect(element.querySelector('c-progress')).toBeTruthy();
  });

  it('esvazia a barra de tempo conforme o relógio do toast', () => {
    fixture.componentInstance.tick(7, 2);
    expect(fixture.componentInstance.remaining()[7]).toBe(60);
  });

  it('tira o toast da lista quando ele some', async () => {
    const id = service.info('Versão atual carregada.');
    await fixture.whenStable();

    fixture.componentInstance.visibleChange(id, false);
    await fixture.whenStable();

    expect(service.toasts()).toEqual([]);
    expect(fixture.nativeElement.querySelector('c-toast')).toBeNull();
  });
});
