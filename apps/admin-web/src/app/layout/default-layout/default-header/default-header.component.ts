/**
 * Cabeçalho do painel, igual ao do template grátis do CoreUI (ADR 029): alterna o menu lateral, abre a busca de
 * telas, mostra os ícones de notificações, tarefas e mensagens, escolhe o tema (claro, escuro ou automático), mostra
 * o usuário logado com a saída e o breadcrumb da rota.
 */

import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';

import {
  AvatarComponent,
  BadgeComponent,
  BreadcrumbRouterComponent,
  ColorModeService,
  ContainerComponent,
  DropdownComponent,
  DropdownHeaderDirective,
  DropdownItemDirective,
  DropdownMenuDirective,
  DropdownToggleDirective,
  FormControlDirective,
  HeaderComponent,
  HeaderNavComponent,
  HeaderTogglerDirective,
  ListGroupDirective,
  ListGroupItemDirective,
  ModalBodyComponent,
  ModalComponent,
  ModalHeaderComponent,
  ModalTitleDirective,
  NavLinkDirective,
  SearchButtonComponent,
  SidebarToggleDirective
} from '@coreui/angular';

import { IconDirective } from '@coreui/icons-angular';

import { AuthSession } from '../../../core/auth/auth-session';
import { navItems } from '../_nav';

@Component({
  selector: 'app-default-header',
  templateUrl: './default-header.component.html',
  imports: [
    AvatarComponent,
    BadgeComponent,
    BreadcrumbRouterComponent,
    ContainerComponent,
    DropdownComponent,
    DropdownHeaderDirective,
    DropdownItemDirective,
    DropdownMenuDirective,
    DropdownToggleDirective,
    FormControlDirective,
    HeaderNavComponent,
    HeaderTogglerDirective,
    IconDirective,
    ListGroupDirective,
    ListGroupItemDirective,
    ModalBodyComponent,
    ModalComponent,
    ModalHeaderComponent,
    ModalTitleDirective,
    NavLinkDirective,
    NgTemplateOutlet,
    SearchButtonComponent,
    SidebarToggleDirective
  ]
})
export class DefaultHeaderComponent extends HeaderComponent {

  readonly #colorModeService = inject(ColorModeService);
  readonly colorMode = this.#colorModeService.colorMode;

  readonly colorModes = [
    { name: 'light', text: 'Claro', icon: 'cilSun' },
    { name: 'dark', text: 'Escuro', icon: 'cilMoon' },
    { name: 'auto', text: 'Automático', icon: 'cilContrast' }
  ];

  readonly icons = computed(() => {
    const currentMode = this.colorMode();
    return this.colorModes.find(mode => mode.name === currentMode)?.icon ?? 'cilSun';
  });

  readonly sidebarId = input('sidebar1');

  readonly #router = inject(Router);
  readonly searchVisible = signal(false);
  readonly searchQuery = signal('');
  readonly searchResults = computed(() => {
    const query = this.searchQuery().trim().toLocaleLowerCase('pt-BR');
    return navItems
      .filter(item => typeof item.url === 'string' && !item.title)
      .map(item => ({ name: item.name ?? '', url: item.url as string }))
      .filter(item => !query || item.name.toLocaleLowerCase('pt-BR').includes(query));
  });

  openResult(url: string): void {
    this.searchVisible.set(false);
    this.searchQuery.set('');
    void this.#router.navigateByUrl(url);
  }

  readonly #session = inject(AuthSession);
  readonly username = this.#session.username();

  logout(): void {
    this.#session.logout();
  }

}
