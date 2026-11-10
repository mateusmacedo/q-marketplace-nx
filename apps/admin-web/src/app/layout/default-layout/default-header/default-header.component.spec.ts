import { provideHttpClient } from '@angular/common/http';
import { provideOAuthClient } from 'angular-oauth2-oidc';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { provideRouter, Router } from '@angular/router';
import {
  AvatarModule,
  BadgeModule,
  BreadcrumbModule,
  ButtonGroupModule,
  DropdownModule,
  GridModule,
  HeaderModule,
  NavModule,
  ProgressModule,
  SidebarModule,
} from '@coreui/angular';
import { IconModule, IconSetService } from '@coreui/icons-angular';
import { iconSubset } from '../../../icons/icon-subset';
import { DefaultHeaderComponent } from './default-header.component';

describe('DefaultHeaderComponent', () => {
  let component: DefaultHeaderComponent;
  let fixture: ComponentFixture<DefaultHeaderComponent>;
  let iconSetService: IconSetService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        GridModule,
        HeaderModule,
        IconModule,
        NavModule,
        BadgeModule,
        AvatarModule,
        DropdownModule,
        BreadcrumbModule,
        SidebarModule,
        ProgressModule,
        ButtonGroupModule,
        ReactiveFormsModule,
        DefaultHeaderComponent,
      ],
      providers: [IconSetService, provideRouter([]), provideHttpClient(), provideOAuthClient()],
    }).compileComponents();
  });

  beforeEach(() => {
    iconSetService = TestBed.inject(IconSetService);
    iconSetService.icons = { ...iconSubset };

    fixture = TestBed.createComponent(DefaultHeaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('nomeia em português o botão que mostra o menu lateral', () => {
    const toggler = (fixture.nativeElement as HTMLElement).querySelector('[cHeaderToggler]');
    expect(toggler?.getAttribute('aria-label')).toBe('Mostrar ou esconder o menu lateral');
  });

  it('lista as telas do menu na busca', () => {
    expect(component.searchResults()).toEqual([{ name: 'Início', url: '/inicio' }]);
  });

  it('filtra as telas pelo texto digitado, sem diferenciar maiúsculas', () => {
    component.searchQuery.set('INÍ');
    expect(component.searchResults().map((result) => result.url)).toEqual(['/inicio']);

    component.searchQuery.set('pedidos');
    expect(component.searchResults()).toEqual([]);
  });

  it('fecha a busca, limpa o texto e navega ao escolher uma tela', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    component.searchVisible.set(true);
    component.searchQuery.set('iní');

    component.openResult('/inicio');

    expect(navigate).toHaveBeenCalledWith('/inicio');
    expect(component.searchVisible()).toBe(false);
    expect(component.searchQuery()).toBe('');
  });
});
