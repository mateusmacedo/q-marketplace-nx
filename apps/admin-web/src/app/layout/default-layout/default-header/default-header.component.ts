/**
 * Cabeçalho do painel: alterna o menu lateral, escolhe o tema (claro, escuro ou automático), mostra o usuário logado
 * com a saída e o breadcrumb da rota.
 */

import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, input } from '@angular/core';

import {
  AvatarComponent,
  BreadcrumbRouterComponent,
  ColorModeService,
  ContainerComponent,
  DropdownComponent,
  DropdownHeaderDirective,
  DropdownItemDirective,
  DropdownMenuDirective,
  DropdownToggleDirective,
  HeaderComponent,
  HeaderNavComponent,
  HeaderTogglerDirective,
  SidebarToggleDirective
} from '@coreui/angular';

import { IconDirective } from '@coreui/icons-angular';

import { AuthSession } from '../../../core/auth/auth-session';

@Component({
  selector: 'app-default-header',
  templateUrl: './default-header.component.html',
  imports: [
    AvatarComponent,
    BreadcrumbRouterComponent,
    ContainerComponent,
    DropdownComponent,
    DropdownHeaderDirective,
    DropdownItemDirective,
    DropdownMenuDirective,
    DropdownToggleDirective,
    HeaderNavComponent,
    HeaderTogglerDirective,
    IconDirective,
    NgTemplateOutlet,
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

  readonly #session = inject(AuthSession);
  readonly username = this.#session.username();

  logout(): void {
    this.#session.logout();
  }

}
