import { Injectable, signal } from '@angular/core';

/** Cor do marcador do toast. */
export type ToastKind = 'success' | 'info' | 'warning' | 'danger';

/** Tempo até o toast sumir, marcado pela barra fina do corpo. */
export const TOAST_DELAY_MS = 5000;

export interface ToastMessage {
  readonly id: number;
  readonly kind: ToastKind;
  readonly title: string;
  readonly message: string;
  readonly createdAt: Date;
}

export interface ToastOptions {
  readonly kind?: ToastKind;
  /** Título do cabeçalho; sem ele, vale o título padrão do tipo. */
  readonly title?: string;
}

const DEFAULT_TITLES: Readonly<Record<ToastKind, string>> = {
  success: 'Pronto',
  info: 'Aviso',
  warning: 'Atenção',
  danger: 'Erro'
};

/**
 * Avisos de sucesso do painel, mostrados pelo `app-toast-host` do layout no toaster do template. Erro de tela fica no
 * lugar onde aconteceu; o toast é para confirmar o que deu certo e para avisos que não pertencem a um formulário.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly #toasts = signal<readonly ToastMessage[]>([]);
  readonly toasts = this.#toasts.asReadonly();
  #nextId = 1;

  /** Mostra o toast e devolve o id, que serve para fechá-lo antes do tempo. */
  show(message: string, options: ToastOptions = {}): number {
    const kind = options.kind ?? 'success';
    const toast: ToastMessage = {
      id: this.#nextId++,
      kind,
      title: options.title ?? DEFAULT_TITLES[kind],
      message,
      createdAt: new Date()
    };
    this.#toasts.update(toasts => [...toasts, toast]);
    return toast.id;
  }

  success(message: string, title?: string): number {
    return this.show(message, { kind: 'success', title });
  }

  info(message: string, title?: string): number {
    return this.show(message, { kind: 'info', title });
  }

  warning(message: string, title?: string): number {
    return this.show(message, { kind: 'warning', title });
  }

  danger(message: string, title?: string): number {
    return this.show(message, { kind: 'danger', title });
  }

  dismiss(id: number): void {
    this.#toasts.update(toasts => toasts.filter(toast => toast.id !== id));
  }
}
